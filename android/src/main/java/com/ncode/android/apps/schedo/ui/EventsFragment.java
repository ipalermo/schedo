/*
 * Copyright 2015 Schedo Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ncode.android.apps.schedo.ui;

import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.support.v4.view.ViewCompat;
import android.text.Spannable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.GenericRequestBuilder;
import com.bumptech.glide.ListPreloader;
import com.ncode.android.apps.schedo.Config;
import com.ncode.android.apps.schedo.R;
import com.ncode.android.apps.schedo.model.TagMetadata;
import com.ncode.android.apps.schedo.provider.ScheduleContract;
import com.ncode.android.apps.schedo.ui.widget.CollectionView;
import com.ncode.android.apps.schedo.ui.widget.CollectionViewCallbacks;
import com.ncode.android.apps.schedo.ui.widget.MessageCardView;
import com.ncode.android.apps.schedo.util.ImageLoader;
import com.ncode.android.apps.schedo.util.PrefUtils;
import com.ncode.android.apps.schedo.util.ThrottledContentObserver;
import com.ncode.android.apps.schedo.util.TimeUtils;
import com.ncode.android.apps.schedo.util.UIUtils;
import com.ncode.android.apps.schedo.util.WiFiUtils;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;

import static com.ncode.android.apps.schedo.util.LogUtils.LOGD;
import static com.ncode.android.apps.schedo.util.LogUtils.LOGE;
import static com.ncode.android.apps.schedo.util.LogUtils.LOGV;
import static com.ncode.android.apps.schedo.util.LogUtils.LOGW;
import static com.ncode.android.apps.schedo.util.LogUtils.makeLogTag;
import static com.ncode.android.apps.schedo.util.UIUtils.buildStyledSnippet;

/**
 * A {@link android.app.ListFragment} showing a list of events. The fragment arguments
 * indicate what is the list of events to show. It may be a set of tag
 * filters or a search query.
 */
public class EventsFragment extends Fragment implements
        LoaderManager.LoaderCallbacks<Cursor>, CollectionViewCallbacks {

    private static final String TAG = makeLogTag(EventsFragment.class);

    // Disable track branding
    public static final String EXTRA_NO_TRACK_BRANDING =
            "com.ncode.android.apps.schedo.extra.NO_TRACK_BRANDING";

    private static final String STATE_EVENT_QUERY_TOKEN = "event_query_token";
    private static final String STATE_ARGUMENTS = "arguments";

    /** The handler message for updating the search query. */
    private static final int MESSAGE_QUERY_UPDATE = 1;
    /** The delay before actual requerying in millisecs. */
    private static final int QUERY_UPDATE_DELAY_MILLIS = 100;
    /** The number of rows ahead to preload images for */
    private static final int ROWS_TO_PRELOAD = 2;

    private static final int ANIM_DURATION = 250;
    private static final int CARD_DISMISS_ACTION_DELAY = MessageCardView.ANIM_DURATION - 50;

    private Context mAppContext;

    // the cursor whose data we are currently displaying
    private int mEventQueryToken;
    private Uri mCurrentUri = ScheduleContract.Events.CONTENT_URI;
    private Cursor mCursor;
    private boolean mIsSearchCursor;
    private boolean mNoTrackBranding;

    // this variable is relevant when we start the events loader, and indicates the desired
    // behavior when load finishes: if true, this is a full reload (for example, because filters
    // have been changed); if not, it's just a refresh because data has changed.
    private boolean mEventDataIsFullReload = false;

    private ImageLoader mImageLoader;
    private int mDefaultEventColor;

    private CollectionView mCollectionView;
    private TextView mEmptyView;
    private View mLoadingView;
    private TagMetadata mTagMetadata = null;

    private boolean mWasPaused = false;

    private static final int HERO_GROUP_ID = 123;

    private Bundle mArguments;

    private DateFormat mDateFormat = DateFormat.getDateInstance(DateFormat.SHORT);
    private DateFormat mTimeFormat = DateFormat.getTimeInstance(DateFormat.SHORT);

    private static final String CARD_ANSWER_ATTENDING_REMOTELY = "CARD_ANSWER_ATTENDING_REMOTELY";
    private static final String CARD_ANSWER_ATTENDING_IN_PERSON = "CARD_ANSWER_ATTENDING_IN_PERSON";
    private static final String CARD_ANSWER_YES = "CARD_ANSWER_YES";
    private static final String CARD_ANSWER_NO = "CARD_ANSWER_NO";

    private ThrottledContentObserver mEventsObserver, mTagsObserver;

    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MESSAGE_QUERY_UPDATE) {
                String query = (String) msg.obj;
                reloadFromArguments(BaseActivity.intentToFragmentArguments(
                        new Intent(Intent.ACTION_SEARCH, ScheduleContract.Events.buildSearchUri(query))));
            }
        }

    };

    private Preloader mPreloader;

    public boolean canCollectionViewScrollUp() {
        return ViewCompat.canScrollVertically(mCollectionView, -1);
    }

    public void setContentTopClearance(int topClearance) {
        mCollectionView.setContentTopClearance(topClearance);
    }

    // Called when there is a change on events in the content provider
    private void onEventsContentChanged() {
        LOGD(TAG, "ThrottledContentObserver fired (events). Content changed.");
        if (!isAdded()) {
            LOGD(TAG, "Ignoring ContentObserver event (Fragment not added).");
            return;
        }

        LOGD(TAG, "Requesting events cursor reload as a result of ContentObserver firing.");
        reloadEventData(false);
    }

    // Called when there is a change in tag metadata in the content provider
    private void onTagsContentChanged() {
        LOGD(TAG, "ThrottledContentObserver fired (tags). Content changed.");
        if (!isAdded()) {
            LOGD(TAG, "Ignoring ContentObserver event (Fragment not added).");
            return;
        }

        LOGD(TAG, "Requesting tags cursor reload as a result of ContentObserver firing.");
        reloadTagMetadata();
    }

    private void reloadEventData(boolean fullReload) {
        LOGD(TAG, "Reloading event data: " + (fullReload ? "FULL RELOAD" : "light refresh"));
        mEventDataIsFullReload = fullReload;
        getLoaderManager().restartLoader(mEventQueryToken, mArguments, EventsFragment.this);
    }

    private void reloadTagMetadata() {
        getLoaderManager().restartLoader(TAG_METADATA_TOKEN, null, EventsFragment.this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mWasPaused = true;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mWasPaused) {
            mWasPaused = false;
            LOGD(TAG, "Reloading data as a result of onResume()");
            mEventsObserver.cancelPendingCallback();
            mTagsObserver.cancelPendingCallback();
            reloadEventData(false);
        }
    }

    public interface Callbacks {
        public void onEventSelected(String eventId, View clickedView);
        public void onTagMetadataLoaded(TagMetadata metadata);
    }

    private static Callbacks sDummyCallbacks = new Callbacks() {
        @Override
        public void onEventSelected(String eventId, View clickedView) {}

        @Override
        public void onTagMetadataLoaded(TagMetadata metadata) {}
    };

    private Callbacks mCallbacks = sDummyCallbacks;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (mImageLoader == null) {
            mImageLoader = new ImageLoader(this.getActivity());
        }

        mDefaultEventColor = getResources().getColor(R.color.default_session_color);

        final TimeZone tz = PrefUtils.getDisplayTimeZone(getActivity());
        mDateFormat.setTimeZone(tz);
        mTimeFormat.setTimeZone(tz);

        if (savedInstanceState != null) {
            mEventQueryToken = savedInstanceState.getInt(STATE_EVENT_QUERY_TOKEN);
            mArguments = savedInstanceState.getParcelable(STATE_ARGUMENTS);
            if (mArguments != null) {
                mCurrentUri = mArguments.getParcelable("_uri");
                mNoTrackBranding = mArguments.getBoolean(EXTRA_NO_TRACK_BRANDING);
            }

            if (mEventQueryToken > 0) {
                // Only if this is a config change should we initLoader(), to reconnect with an
                // existing loader. Otherwise, the loader will be init'd when reloadFromArguments
                // is called.
                getLoaderManager().initLoader(mEventQueryToken, null, EventsFragment.this);
            }
        }

        reloadTagMetadata();
    }

    private boolean useExpandedMode() {
        if (mCurrentUri != null && ScheduleContract.Events.CONTENT_URI.equals(mCurrentUri)) {
            // If showing all events (landing page) do not use expanded mode,
            // show info as condensed as possible
            return false;
        }
        return true;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mAppContext = getActivity().getApplicationContext();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        ViewGroup root = (ViewGroup) inflater.inflate(R.layout.fragment_events, container, false);
        mCollectionView = (CollectionView) root.findViewById(R.id.events_collection_view);
        mPreloader = new Preloader(ROWS_TO_PRELOAD);
        mCollectionView.setOnScrollListener(mPreloader);
        mEmptyView = (TextView) root.findViewById(R.id.empty_text);
        mLoadingView = root.findViewById(R.id.loading);
        return root;
    }

    void reloadFromArguments(Bundle arguments) {
        // Load new arguments
        if (arguments == null) {
            arguments = new Bundle();
        } else {
            // since we might make changes, don't meddle with caller's copy
            arguments = (Bundle) arguments.clone();
        }

        // save arguments so we can reuse it when reloading from content observer events
        mArguments = arguments;

        LOGD(TAG, "EventsFragment reloading from arguments: " + arguments);
        mCurrentUri = arguments.getParcelable("_uri");
        if (mCurrentUri == null) {
            // if no URI, default to all events URI
            LOGD(TAG, "EventsFragment did not get a URL, defaulting to all events.");
            arguments.putParcelable("_uri", ScheduleContract.Events.CONTENT_URI);
            mCurrentUri = ScheduleContract.Events.CONTENT_URI;
        }

        mNoTrackBranding = mArguments.getBoolean(EXTRA_NO_TRACK_BRANDING);

        if (ScheduleContract.Events.isSearchUri(mCurrentUri)) {
            mEventQueryToken = EventsQuery.SEARCH_TOKEN;
        } else {
            mEventQueryToken = EventsQuery.NORMAL_TOKEN;
        }

        LOGD(TAG, "EventsFragment reloading, uri=" + mCurrentUri + ", expanded=" + useExpandedMode());

        reloadEventData(true); // full reload
        if (mTagMetadata == null) {
            reloadTagMetadata();
        }
    }

    void requestQueryUpdate(String query) {
        mHandler.removeMessages(MESSAGE_QUERY_UPDATE);
        mHandler.sendMessageDelayed(Message.obtain(mHandler, MESSAGE_QUERY_UPDATE, query),
                QUERY_UPDATE_DELAY_MILLIS);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (!(activity instanceof Callbacks)) {
            throw new ClassCastException("Activity must implement fragment's callbacks.");
        }

        mAppContext = getActivity().getApplicationContext();
        mCallbacks = (Callbacks) activity;
        mEventsObserver = new ThrottledContentObserver(new ThrottledContentObserver.Callbacks() {
            @Override
            public void onThrottledContentObserverFired() {
                onEventsContentChanged();
            }
        });
        mTagsObserver = new ThrottledContentObserver(new ThrottledContentObserver.Callbacks() {
            @Override
            public void onThrottledContentObserverFired() {
                onTagsContentChanged();
            }
        });
        activity.getContentResolver().registerContentObserver(
                ScheduleContract.Events.CONTENT_URI, true, mEventsObserver);
        activity.getContentResolver().registerContentObserver(
                ScheduleContract.Tags.CONTENT_URI, true, mTagsObserver);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        sp.registerOnSharedPreferenceChangeListener(mPrefChangeListener);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallbacks = sDummyCallbacks;
        getActivity().getContentResolver().unregisterContentObserver(mEventsObserver);
        getActivity().getContentResolver().unregisterContentObserver(mTagsObserver);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getActivity());
        sp.unregisterOnSharedPreferenceChangeListener(mPrefChangeListener);
    }

    public void animateReload() {
        //int curTop = mCollectionView.getTop();
        mCollectionView.setAlpha(0);
        //mCollectionView.setTop(getResources().getDimensionPixelSize(R.dimen.browse_sessions_anim_amount));
        //mCollectionView.animate().y(curTop).alpha(1).setDuration(ANIM_DURATION).setInterpolator(new DecelerateInterpolator());
        mCollectionView.animate().alpha(1).setDuration(ANIM_DURATION).setInterpolator(new DecelerateInterpolator());
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_EVENT_QUERY_TOKEN, mEventQueryToken);
        outState.putParcelable(STATE_ARGUMENTS, mArguments);
    }

    // LoaderCallbacks interface
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle data) {
        LOGD(TAG, "onCreateLoader, id=" + id + ", data=" + data);
        final Intent intent = BaseActivity.fragmentArgumentsToIntent(data);
        Uri eventsUri = intent.getData();
        if ((id == EventsQuery.NORMAL_TOKEN || id == EventsQuery.SEARCH_TOKEN) && eventsUri == null) {
            LOGD(TAG, "intent.getData() is null, setting to default events search");
            eventsUri = ScheduleContract.Events.CONTENT_URI;
        }
        Loader<Cursor> loader = null;
        String liveStreamedOnlySelection = UIUtils.shouldShowLiveEventsOnly(getActivity())
                ? "IFNULL(" + ScheduleContract.Events.EVENT_LIVESTREAM_URL + ",'')!=''"
                : null;
        if (id == EventsQuery.NORMAL_TOKEN) {
            LOGD(TAG, "Creating events loader for " + eventsUri + ", selection " + liveStreamedOnlySelection);
            loader = new CursorLoader(getActivity(), eventsUri, EventsQuery.NORMAL_PROJECTION,
                    liveStreamedOnlySelection, null, ScheduleContract.Events.SORT_BY_TYPE_THEN_TIME);
        } else if (id == EventsQuery.SEARCH_TOKEN) {
            LOGD(TAG, "Creating search loader for " + eventsUri + ", selection " + liveStreamedOnlySelection);
            loader = new CursorLoader(getActivity(), eventsUri, EventsQuery.SEARCH_PROJECTION,
                    liveStreamedOnlySelection, null, ScheduleContract.Events.SORT_BY_TYPE_THEN_TIME);
        } else if (id == TAG_METADATA_TOKEN) {
            LOGD(TAG, "Creating metadata loader");
            loader = TagMetadata.createCursorLoader(getActivity());
        }
        return loader;
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        if (getActivity() == null) {
            return;
        }

        int token = loader.getId();
        LOGD(TAG, "Loader finished: "  + (token == EventsQuery.NORMAL_TOKEN ? "events" :
                token == EventsQuery.SEARCH_TOKEN ? "search" : token == TAG_METADATA_TOKEN ? "tags" :
                        "unknown"));
        if (token == EventsQuery.NORMAL_TOKEN || token == EventsQuery.SEARCH_TOKEN) {
            if (mCursor != null && mCursor != cursor) {
                mCursor.close();
            }
            mCursor = cursor;
            mIsSearchCursor = token == EventsQuery.SEARCH_TOKEN;
            LOGD(TAG, "Cursor has " + mCursor.getCount() + " items. Will now update collection view.");
            updateCollectionView();
        } else if (token == TAG_METADATA_TOKEN) {
            mTagMetadata = new TagMetadata(cursor);
            cursor.close();
            updateCollectionView();
            mCallbacks.onTagMetadataLoaded(mTagMetadata);
        } else {
            LOGD(TAG, "Query complete, Not Actionable: " + token);
            cursor.close();
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefChangeListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if (isAdded()) {
                if (PrefUtils.PREF_LOCAL_TIMES.equals(key)) {
                    updateCollectionView();
                } else if (PrefUtils.PREF_ATTENDEE_AT_VENUE.equals(key)) {
                    if (mCursor != null) {
                        reloadEventData(true);
                    }
                }
            }
        }
    };

    private void updateCollectionView() {
        if (mCursor == null || mTagMetadata == null) {
            LOGD(TAG, "updateCollectionView: not ready yet... " + (mCursor == null ? "no cursor." :
                    "no tag metadata."));
            // not ready!
            return;
        }
        LOGD(TAG, "EventsFragment updating CollectionView... " + (mEventDataIsFullReload ?
                "(FULL RELOAD)" : "(light refresh)"));
        mCursor.moveToPosition(-1);
        int itemCount = mCursor.getCount();

        mMaxDataIndexAnimated = 0;

        CollectionView.Inventory inv;
        if (itemCount == 0) {
            showEmptyView();
            inv = new CollectionView.Inventory();
        } else {
            hideEmptyView();
            inv = prepareInventory();
        }

        Parcelable state = null;
        if (!mEventDataIsFullReload) {
            // it's not a full reload, so we want to keep scroll position, etc
            state = mCollectionView.onSaveInstanceState();
        }
        LOGD(TAG, "Updating CollectionView with inventory, # groups = " + inv.getGroupCount()
                + " total items = " + inv.getTotalItemCount());
        mCollectionView.setCollectionAdapter(this);
        mCollectionView.updateInventory(inv, mEventDataIsFullReload);
        if (state != null) {
            mCollectionView.onRestoreInstanceState(state);
        }
        mEventDataIsFullReload = false;
    }

    private void hideEmptyView() {
        mEmptyView.setVisibility(View.GONE);
        mLoadingView.setVisibility(View.GONE);
    }

    private void showEmptyView() {
        final String searchQuery = ScheduleContract.Events.isSearchUri(mCurrentUri) ?
                ScheduleContract.Events.getSearchQuery(mCurrentUri) : null;

        if (mCurrentUri.equals(ScheduleContract.Events.CONTENT_URI)) {
            // if showing all events, the empty view should say "loading..." because
            // the only reason we would have no events at all is if we are currently
            // preparing the database from the bootstrap data, which should only take a few
            // seconds.
            mEmptyView.setVisibility(View.GONE);
            mLoadingView.setVisibility(View.VISIBLE);
        } else if (ScheduleContract.Events.isUnscheduledEventsInInterval(mCurrentUri)) {
            // Showing events in a given interval, so say "No events in this time slot."
            mEmptyView.setText(R.string.no_matching_events_in_interval);
            mEmptyView.setVisibility(View.VISIBLE);
            mLoadingView.setVisibility(View.GONE);
        } else if (ScheduleContract.Events.isSearchUri(mCurrentUri)
                && (TextUtils.isEmpty(searchQuery) || "*".equals(searchQuery))) {
            // Empty search query (for example, user hasn't started to type the query yet),
            // so don't show an empty view.
            mEmptyView.setText("");
            mEmptyView.setVisibility(View.VISIBLE);
            mLoadingView.setVisibility(View.GONE);
        } else {
            // Showing events as a result of search or filter, so say "No matching events."
            mEmptyView.setText(R.string.no_matching_sessions);
            mEmptyView.setVisibility(View.VISIBLE);
            mLoadingView.setVisibility(View.GONE);
        }
    }


    // Creates the CollectionView groups based on the cursor data.
    private CollectionView.Inventory prepareInventory() {
        LOGD(TAG, "Preparing collection view inventory.");
        ArrayList<CollectionView.InventoryGroup> pastGroups =
                new ArrayList<CollectionView.InventoryGroup>();
        ArrayList<CollectionView.InventoryGroup> futureGroups =
                new ArrayList<CollectionView.InventoryGroup>();
        HashMap<String, CollectionView.InventoryGroup> pastGroupsByName =
                new HashMap<String, CollectionView.InventoryGroup>();
        HashMap<String, CollectionView.InventoryGroup> futureGroupsByName =
                new HashMap<String, CollectionView.InventoryGroup>();
        CollectionView.InventoryGroup heroGroup = null;

        mCursor.moveToPosition(-1);
        int nextGroupId = HERO_GROUP_ID + 1000; // to avoid conflict with the special hero group ID
        LOGD(TAG, "Total cursor data items: " + mCursor.getCount());
        int dataIndex = -1;
        final long now = UIUtils.getCurrentTime(mAppContext);
        final boolean conferenceEnded = TimeUtils.hasConferenceEnded(mAppContext);
        LOGD(TAG, "conferenceEnded=" + conferenceEnded);

        final boolean expandedMode = useExpandedMode();
        final int displayCols = getResources().getInteger(expandedMode ?
                R.integer.explore_2nd_level_grid_columns : R.integer.explore_1st_level_grid_columns);
        LOGD(TAG, "Using " + displayCols + " columns.");
        mPreloader.setDisplayCols(displayCols);

        while (mCursor.moveToNext()) {
            // For each data item, we decide what group it should appear under, then
            // we add it to that group (and create the group if it doesn't exist yet).

            long eventEnd = Long.parseLong(mCursor.getString(
                    mCursor.getColumnIndex(ScheduleContract.Events.EVENT_DAYS)).split(",")[0]);

            ++dataIndex;
            boolean showAsPast = !conferenceEnded && eventEnd < now;
            String groupLabel;

            if (expandedMode) {
                String tags = mCursor.getString(mCursor.getColumnIndex(ScheduleContract.Events.SESSION_TAGS));
                TagMetadata.Tag groupTag = tags == null ? null
                        : mTagMetadata.getGroupTag(tags.split(","), Config.Tags.EVENT_GROUPING_TAG_CATEGORY);
                if (groupTag != null) {
                    groupLabel = groupTag.getName();
                } else {
                    groupLabel = getString(R.string.others);
                }
                groupLabel += (showAsPast ? " (" + getString(R.string.event_finished) + ")" : "");
            } else {
                groupLabel = showAsPast ? getString(R.string.ended_events) : "";
            }

            LOGV(TAG, "Data item #" + dataIndex + ", eventEnd=" + eventEnd + ", groupLabel="
                    + groupLabel + " showAsPast=" + showAsPast);

            CollectionView.InventoryGroup group;

            // should this item be the hero group?
            if (!useExpandedMode() && !showAsPast && heroGroup == null) {
                // yes, this item is the hero
                LOGV(TAG, "This item is the hero.");
                group = heroGroup = new CollectionView.InventoryGroup(HERO_GROUP_ID)
                        .setDisplayCols(1)  // hero item spans all columns
                        .setShowHeader(false).setHeaderLabel("");
            } else {
                // "list" and "map" are just shorthand variables pointing to the right list and map
                ArrayList<CollectionView.InventoryGroup> list = showAsPast ? pastGroups : futureGroups;
                HashMap<String, CollectionView.InventoryGroup> map = showAsPast ? pastGroupsByName :
                        futureGroupsByName;

                // Create group, if it doesn't exist yet
                if (!map.containsKey(groupLabel)) {
                    LOGV(TAG, "Creating new group: " + groupLabel);
                    group = new CollectionView.InventoryGroup(nextGroupId++)
                            .setDisplayCols(displayCols)
                            .setShowHeader(!TextUtils.isEmpty(groupLabel))
                            .setHeaderLabel(groupLabel);
                    map.put(groupLabel, group);
                    list.add(group);
                } else {
                    LOGV(TAG, "Adding to existing group: " + groupLabel);
                    group = map.get(groupLabel);
                }
            }

            // add this item to the group
            LOGV(TAG, "...adding to group '" + groupLabel + "' with custom data index " + dataIndex);
            group.addItemWithCustomDataIndex(dataIndex);
        }

        // prepare the final groups list
        ArrayList<CollectionView.InventoryGroup> groups = new ArrayList<CollectionView.InventoryGroup>();
        if (heroGroup != null) {
            groups.add(heroGroup); // start with the hero
        }
        groups.addAll(futureGroups); // then all future events
        groups.addAll(pastGroups); // then all past events
        LOGD(TAG, "Total: hero " + (heroGroup == null ? "absent" : "present")
                + " " + futureGroups.size() + " future groups, "
                + " " + pastGroups.size() + " past groups, total " + groups.size());

        // the first group doesn't need a header label, because it's the "default group"
        //if (groups.size() > 0) {
        //    groups.get(0).setHeaderLabel("").setShowHeader(false);
        //}

        // finally, assemble the inventory and we're done
        CollectionView.Inventory inventory = new CollectionView.Inventory();
        for (CollectionView.InventoryGroup g : groups) {
            inventory.addGroup(g);
        }
        return inventory;
    }

    @Override
    public View newCollectionHeaderView(Context context, ViewGroup parent) {
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        return inflater.inflate(R.layout.list_item_explore_header, parent, false);
    }

    @Override
    public void bindCollectionHeaderView(Context context, View view, int groupId, String groupLabel) {
        TextView tv = (TextView) view.findViewById(android.R.id.text1);
        if (tv != null) {
            tv.setText(groupLabel);
        }
    }

    @Override
    public View newCollectionItemView(Context context, int groupId, ViewGroup parent) {
        final LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        int layoutId;

        if (useExpandedMode()) {
            layoutId = R.layout.list_item_event;
        } else {
            // Group HERO_GROUP_ID is the hero -- use a larger layout
            layoutId = (groupId == HERO_GROUP_ID) ? R.layout.list_item_session_hero :
                    R.layout.list_item_event_summarized;
        }

        return inflater.inflate(layoutId, parent, false);
    }

    private StringBuilder mBuffer = new StringBuilder();

    private int mMaxDataIndexAnimated = 0;

    @Override
    public void bindCollectionItemView(Context context, View view, int groupId, int indexInGroup, int dataIndex, Object tag) {
        if (mCursor == null || !mCursor.moveToPosition(dataIndex)) {
            LOGW(TAG, "Can't bind collection view item, dataIndex=" + dataIndex +
                    (mCursor == null ? ": cursor is null" : ": bad data index."));
            return;
        }

        final String eventId = mCursor.getString(EventsQuery.EVENT_ID);
        if (eventId == null) {
            return;
        }

        // first, read event info from cursor and put it in convenience variables
        final String eventTitle = mCursor.getString(EventsQuery.TITLE);
        final String speakerNames = mCursor.getString(EventsQuery.SPEAKER_NAMES);
        final String eventAbstract = mCursor.getString(EventsQuery.ABSTRACT);
        final String[] eventDays = mCursor.getString(EventsQuery.EVENT_DAYS).split(",");
        final long eventStart = Long.valueOf(eventDays[0]).longValue();
        final long eventEnd = Long.valueOf(eventDays[eventDays.length/2]).longValue();
        //final String roomName = mCursor.getString(EventsQuery.ROOM_NAME);
        int eventColor = mCursor.getInt(EventsQuery.COLOR);
        eventColor = eventColor == 0 ? getResources().getColor(R.color.default_event_color)
                : eventColor;
        int darkEventColor = 0;
        final String snippet = mIsSearchCursor ? mCursor.getString(EventsQuery.SNIPPET) : null;
        final Spannable styledSnippet = mIsSearchCursor ? buildStyledSnippet(snippet) : null;
        final boolean starred = mCursor.getInt(EventsQuery.IN_MY_SCHEDULE) != 0;
        final String[] tags = mCursor.getString(EventsQuery.TAGS).split(",");

        // now let's compute a few pieces of information from the data, which we will use
        // later to decide what to render where
        final boolean hasLivestream = !TextUtils.isEmpty(mCursor.getString(
                EventsQuery.LIVESTREAM_URL));
        final long now = UIUtils.getCurrentTime(context);
        final boolean happeningNow = now >= eventStart && now <= eventEnd;

        // text that says "LIVE" if event is live, or empty if event is not live
        final String liveNowText = hasLivestream ? " " + UIUtils.getLiveBadgeText(context,
                eventStart, eventEnd) : "";

        // get reference to all the views in the layout we will need
        final TextView titleView = (TextView) view.findViewById(R.id.session_title);
        final TextView subtitleView = (TextView) view.findViewById(R.id.session_subtitle);
        final TextView shortSubtitleView = (TextView) view.findViewById(R.id.session_subtitle_short);
        final TextView snippetView = (TextView) view.findViewById(R.id.session_snippet);
        final TextView abstractView = (TextView) view.findViewById(R.id.session_abstract);
        final TextView categoryView = (TextView) view.findViewById(R.id.session_category);
        final View eventTargetView = view.findViewById(R.id.session_target);

        if (eventColor == 0) {
            // use default
            eventColor = mDefaultEventColor;
        }

        if (mNoTrackBranding) {
            eventColor = getResources().getColor(R.color.no_track_branding_session_color);
        }

        darkEventColor = UIUtils.scaleSessionColorToDefaultBG(eventColor);

        ImageView photoView = (ImageView) view.findViewById(R.id.session_photo_colored);
        if (photoView != null) {
            if (!mPreloader.isDimensSet()) {
                final ImageView finalPhotoView = photoView;
                photoView.post(new Runnable() {
                    @Override
                    public void run() {
                        mPreloader.setDimens(finalPhotoView.getWidth(), finalPhotoView.getHeight());
                    }
                });
            }
            // colored
            photoView.setColorFilter(mNoTrackBranding
                    ? new PorterDuffColorFilter(
                    getResources().getColor(R.color.no_track_branding_session_tile_overlay),
                    PorterDuff.Mode.SRC_ATOP)
                    : UIUtils.makeSessionImageScrimColorFilter(darkEventColor));
        } else {
            photoView = (ImageView) view.findViewById(R.id.session_photo);
        }
        ViewCompat.setTransitionName(photoView, "photo_" + eventId);

        // when we load a photo, it will fade in from transparent so the
        // background of the container must be the event color to avoid a white flash
        ViewParent parent = photoView.getParent();
        if (parent != null && parent instanceof View) {
            ((View) parent).setBackgroundColor(darkEventColor);
        } else {
            photoView.setBackgroundColor(darkEventColor);
        }

        String photo = mCursor.getString(EventsQuery.PHOTO_URL);
        if (!TextUtils.isEmpty(photo)) {
            mImageLoader.loadImage(photo, photoView, true /*crop*/);
        } else {
            // cleaning the (potentially) recycled photoView, in case this event has no photo:
            photoView.setImageDrawable(null);
        }

        // render title
        titleView.setText(eventTitle == null ? "?" : eventTitle);

        // render subtitle into either the subtitle view, or the short subtitle view, as available
        if (subtitleView != null) {
            subtitleView.setText(UIUtils.formatEventSubtitle(
                    eventStart, eventEnd, mBuffer, context) + liveNowText);
        } else if (shortSubtitleView != null) {
            shortSubtitleView.setText(UIUtils.formatEventSubtitle(
                    eventStart, eventEnd, mBuffer, context, true) + liveNowText);
        }

        // render category
        if (categoryView != null) {
            TagMetadata.Tag groupTag = mTagMetadata.getGroupTag(tags, Config.Tags.EVENT_GROUPING_TAG_CATEGORY);
            if (groupTag != null && !Config.Tags.EVENTS.equals(groupTag.getId())) {
                categoryView.setText(groupTag.getName());
                categoryView.setVisibility(View.VISIBLE);
            } else {
                categoryView.setVisibility(View.GONE);
            }
        }

        // if a snippet view is available, render the event snippet there.
        if (snippetView != null) {
            if (mIsSearchCursor) {
                // render the search snippet into the snippet view
                snippetView.setText(styledSnippet);
            } else {
                // render speaker names and abstracts into the snippet view
                mBuffer.setLength(0);
                if (!TextUtils.isEmpty(speakerNames)) {
                    mBuffer.append(speakerNames).append(". ");
                }
                if (!TextUtils.isEmpty(eventAbstract)) {
                    mBuffer.append(eventAbstract);
                }
                snippetView.setText(mBuffer.toString());
            }
        }

        if (abstractView != null && !mIsSearchCursor) {
            // render speaker names and abstracts into the abstract view
            mBuffer.setLength(0);
            if (!TextUtils.isEmpty(speakerNames)) {
                mBuffer.append(speakerNames).append("\n\n");
            }
            if (!TextUtils.isEmpty(eventAbstract)) {
                mBuffer.append(eventAbstract);
            }
            abstractView.setText(mBuffer.toString());
        }

        // show or hide the "in my schedule" indicator
        view.findViewById(R.id.indicator_in_schedule).setVisibility(starred ? View.VISIBLE
                : View.INVISIBLE);

        // if we are in condensed mode and this card is the hero card (big card at the top
        // of the screen), set up the message card if necessary.
        if (!useExpandedMode() && groupId == HERO_GROUP_ID) {
            // this is the hero view, so we might want to show a message card
            final boolean cardShown = setupMessageCard(view);

            // if this is the wide hero layout, show or hide the card or the event abstract
            // view, as appropriate (they are mutually exclusive).
            final View cardContainer = view.findViewById(R.id.message_card_container_wide);
            final View abstractContainer = view.findViewById(R.id.session_abstract);
            if (cardContainer != null && abstractContainer != null) {
                cardContainer.setVisibility(cardShown ? View.VISIBLE : View.GONE);
                abstractContainer.setVisibility(cardShown ? View.GONE : View.VISIBLE);
                abstractContainer.setBackgroundColor(darkEventColor);
            }
        }

        // if this event is live right now, display the "LIVE NOW" icon on top of it
        View liveNowBadge = view.findViewById(R.id.live_now_badge);
        if (liveNowBadge != null) {
            liveNowBadge.setVisibility(happeningNow && hasLivestream ? View.VISIBLE : View.GONE);
        }

        // if this view is clicked, open the event details view
        final View finalPhotoView = photoView;
        eventTargetView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCallbacks.onEventSelected(eventId, finalPhotoView);
            }
        });

        // animate this card
        if (dataIndex > mMaxDataIndexAnimated) {
            mMaxDataIndexAnimated = dataIndex;
        }
    }

    private boolean setupMessageCard(View hero) {
        MessageCardView card = (MessageCardView) hero.findViewById(R.id.message_card);
        if (card == null) {
            LOGE(TAG, "Message card not found in UI (R.id.message_card).");
            return false;
        }
        if (!PrefUtils.hasAnsweredLocalOrRemote(getActivity()) &&
                !TimeUtils.hasConferenceEnded(getActivity())) {
            // show the "in person" vs "remote" card
            setupLocalOrRemoteCard(card);
            return true;
        } else if (WiFiUtils.shouldOfferToSetupWifi(getActivity(), true)) {
            // show wifi setup card
            setupWifiOfferCard(card);
            return true;
        } else if (PrefUtils.shouldOfferIOExtended(getActivity(), true)) {
            // show the I/O extended card
            setupIOExtendedCard(card);
            return true;
        } else {
            card.setVisibility(View.GONE);
            return false;
        }
    }

    private void setupLocalOrRemoteCard(final MessageCardView card) {
        card.setText(getString(R.string.question_local_or_remote));
        card.setButton(0, getString(R.string.attending_remotely), CARD_ANSWER_ATTENDING_REMOTELY,
                false, 0);
        card.setButton(1, getString(R.string.attending_in_person), CARD_ANSWER_ATTENDING_IN_PERSON,
                true, 0);
        final Context context = getActivity().getApplicationContext();
        final Activity activity = getActivity();
        card.setListener(new MessageCardView.OnMessageCardButtonClicked() {
            @Override
            public void onMessageCardButtonClicked(final String tag) {
                final boolean inPerson = CARD_ANSWER_ATTENDING_IN_PERSON.equals(tag);
                card.dismiss(true);

                if (activity != null) {
                    Toast.makeText(activity, inPerson ? R.string.explore_attending_in_person_toast
                            : R.string.explore_attending_remotely_toast, Toast.LENGTH_LONG).show();
                }

                // post delayed to give card time to animate
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        PrefUtils.setAttendeeAtVenue(context, inPerson);
                        PrefUtils.markAnsweredLocalOrRemote(context);
                    }
                }, CARD_DISMISS_ACTION_DELAY);
            }
        });
        card.show();
    }

    private void setupWifiOfferCard(final MessageCardView card) {
        card.setText(getString(TimeUtils.hasConferenceStarted(getActivity()) ?
                R.string.question_setup_wifi_after_i_o_start :
                R.string.question_setup_wifi_before_i_o_start));
        card.setButton(0, getString(R.string.no_thanks), CARD_ANSWER_NO,
                false, 0);
        card.setButton(1, getString(R.string.setup_wifi_yes), CARD_ANSWER_YES,
                true, 0);
        final Context context = getActivity().getApplicationContext();
        card.setListener(new MessageCardView.OnMessageCardButtonClicked() {
            @Override
            public void onMessageCardButtonClicked(final String tag) {
                card.dismiss(true);

                // post delayed to give card time to animate
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (CARD_ANSWER_YES.equals(tag)) {
                            WiFiUtils.showWiFiDialog(EventsFragment.this.getActivity());
                        } else {
                            PrefUtils.markDeclinedWifiSetup(context);
                        }
                    }
                }, CARD_DISMISS_ACTION_DELAY);
            }
        });
        card.show();
    }

    private void setupIOExtendedCard(final MessageCardView card) {
        card.setText(getString(R.string.question_i_o_extended));
        card.setButton(0, getString(R.string.no_thanks), CARD_ANSWER_NO,
                false, 0);
        card.setButton(1, getString(R.string.browse_events), CARD_ANSWER_YES,
                true, 0);
        card.setListener(new MessageCardView.OnMessageCardButtonClicked() {
            @Override
            public void onMessageCardButtonClicked(final String tag) {
                card.dismiss(true);

                // post delayed to give card time to animate
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (CARD_ANSWER_YES.equals(tag)) {
                            Intent intent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(Config.IO_EXTENDED_LINK));
                            startActivity(intent);
                        }
                        PrefUtils.markDismissedIOExtendedCard(EventsFragment.this.getActivity());
                    }
                }, CARD_DISMISS_ACTION_DELAY);
            }
        });
        card.show();
    }

    private void animateEventAppear(final View view) {
    }

    private class Preloader extends ListPreloader<String> {

        private int[] photoDimens;
        private int displayCols;

        public Preloader(int maxPreload) {
            super(maxPreload);
        }

        public void setDisplayCols(int displayCols) {
            this.displayCols = displayCols;
        }

        public boolean isDimensSet() {
            return photoDimens != null;
        }

        public void setDimens(int width, int height) {
            if (photoDimens == null) {
                photoDimens = new int[] { width, height };
            }
        }

        @Override
        protected int[] getDimensions(String s) {
            return photoDimens;
        }

        @Override
        protected List<String> getItems(int start, int end) {
            // Our start and end are rows, we need to adjust them into data columns
            // The keynote is 1 row with 1 data item, so we need to adjust.
            int keynoteDataOffset = (displayCols - 1);
            int dataStart = start * displayCols - keynoteDataOffset;
            int dataEnd = end * displayCols - keynoteDataOffset;
            List<String> urls = new ArrayList<String>();
            if (mCursor != null) {
                for (int i = dataStart; i < dataEnd; i++) {
                    if (mCursor.moveToPosition(i)) {
                        urls.add(mCursor.getString(EventsQuery.PHOTO_URL));
                    }
                }
            }
            return urls;
        }

        @Override
        protected GenericRequestBuilder getRequestBuilder(String url) {
            return mImageLoader.beginImageLoad(url, null, true /*crop*/);
        }
    }

    /**
     * {@link com.ncode.android.apps.schedo.provider.ScheduleContract.Events}
     * query parameters.
     */
    private interface EventsQuery {
        int NORMAL_TOKEN = 0x1;
        int SEARCH_TOKEN = 0x3;

        String[] NORMAL_PROJECTION = {
                BaseColumns._ID,
                ScheduleContract.Events.EVENT_ID,
                ScheduleContract.Events.EVENT_TITLE,
                ScheduleContract.Events.EVENT_IN_MY_SCHEDULE,
                ScheduleContract.Events.EVENT_DAYS,
                ScheduleContract.Events.EVENT_HASHTAG,
                ScheduleContract.Events.EVENT_URL,
                ScheduleContract.Events.EVENT_LIVESTREAM_URL,
                ScheduleContract.Events.EVENT_TAGS,
                ScheduleContract.Events.EVENT_SPEAKER_NAMES,
                ScheduleContract.Events.EVENT_ABSTRACT,
                ScheduleContract.Events.EVENT_COLOR,
                ScheduleContract.Events.EVENT_PHOTO_URL,
        };

        String[] SEARCH_PROJECTION = {
                BaseColumns._ID,
                ScheduleContract.Events.EVENT_ID,
                ScheduleContract.Events.EVENT_TITLE,
                ScheduleContract.Events.EVENT_IN_MY_SCHEDULE,
                ScheduleContract.Events.EVENT_DAYS,
                ScheduleContract.Events.EVENT_HASHTAG,
                ScheduleContract.Events.EVENT_URL,
                ScheduleContract.Events.EVENT_LIVESTREAM_URL,
                ScheduleContract.Events.EVENT_TAGS,
                ScheduleContract.Events.EVENT_SPEAKER_NAMES,
                ScheduleContract.Events.EVENT_ABSTRACT,
                ScheduleContract.Events.EVENT_COLOR,
                ScheduleContract.Events.EVENT_PHOTO_URL,
                ScheduleContract.Events.SEARCH_SNIPPET,
        };


        int _ID = 0;
        int EVENT_ID = 1;
        int TITLE = 2;
        int IN_MY_SCHEDULE = 3;
        int EVENT_DAYS = 4;
        int HASHTAGS = 8;
        int URL = 9;
        int LIVESTREAM_URL = 10;
        int TAGS = 11;
        int SPEAKER_NAMES = 12;
        int ABSTRACT = 13;
        int COLOR = 14;
        int PHOTO_URL = 15;
        int SNIPPET = 16;
    }

    private static final int TAG_METADATA_TOKEN = 0x4;
}
