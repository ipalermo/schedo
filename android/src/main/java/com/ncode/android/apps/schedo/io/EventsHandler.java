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

package com.ncode.android.apps.schedo.io;

import android.content.ContentProviderOperation;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.ncode.android.apps.schedo.Config;
import com.ncode.android.apps.schedo.R;
import com.ncode.android.apps.schedo.io.model.Day;
import com.ncode.android.apps.schedo.io.model.Event;
import com.ncode.android.apps.schedo.io.model.Session;
import com.ncode.android.apps.schedo.io.model.Speaker;
import com.ncode.android.apps.schedo.io.model.Tag;
import com.ncode.android.apps.schedo.io.model.Video;
import com.ncode.android.apps.schedo.provider.ScheduleContract;
import com.ncode.android.apps.schedo.provider.ScheduleDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static com.ncode.android.apps.schedo.util.LogUtils.LOGD;
import static com.ncode.android.apps.schedo.util.LogUtils.LOGE;
import static com.ncode.android.apps.schedo.util.LogUtils.LOGW;
import static com.ncode.android.apps.schedo.util.LogUtils.makeLogTag;

public class EventsHandler extends JSONHandler {
    private static final String TAG = makeLogTag(EventsHandler.class);
    private HashMap<String, Event> mEvents = new HashMap<String, Event>();
    private HashMap<String, Tag> mTagMap = null;
    private HashMap<String, Video> mVideoMap = null;
    private HashMap<String, Day> mEventDays = null;
    private int mDefaultEventColor;


    public EventsHandler(Context context) {
        super(context);
        mDefaultEventColor = mContext.getResources().getColor(R.color.default_session_color);
    }

    @Override
    public void process(JsonElement element) {
        for (Event event : new Gson().fromJson(element, Event[].class)) {
            mEvents.put(event.id, event);
        }
    }

    @Override
    public void makeContentProviderOperations(ArrayList<ContentProviderOperation> list) {
        Uri uri = ScheduleContract.addCallerIsSyncAdapterParameter(
                ScheduleContract.Events.CONTENT_URI);

        // build a map of event to event import hashcode so we know what to update,
        // what to insert, and what to delete
        HashMap<String, String> eventHashCodes = loadEventHashCodes();
        boolean incrementalUpdate = (eventHashCodes != null) && (eventHashCodes.size() > 0);

        // set of events that we want to keep after the sync
        HashSet<String> eventsToKeep = new HashSet<String>();

        if (incrementalUpdate) {
            LOGD(TAG, "Doing incremental update for events.");
        } else {
            LOGD(TAG, "Doing full (non-incremental) update for events.");
            list.add(ContentProviderOperation.newDelete(uri).build());
        }

        int updatedEvents = 0;
        for (Event event : mEvents.values()) {
            // Set the event grouping order in the object, so it can be used in hash calculation
            event.groupingOrder = computeTypeOrder(event);

            // compute the incoming event's hashcode to figure out if we need to update
            String hashCode = event.getImportHashCode();
            eventsToKeep.add(event.id);

            // add event, if necessary
            if (!incrementalUpdate || !eventHashCodes.containsKey(event.id) ||
                        !eventHashCodes.get(event.id).equals(hashCode)) {
                ++updatedEvents;
                boolean isNew = !incrementalUpdate || !eventHashCodes.containsKey(event.id);
                buildEvent(isNew, event, list);

                // add relationships to sessions and tags
                buildEventVideosMapping(event, list);
                buildTagsMapping(event, list);
            }
        }

        int deletedEvents = 0;
        if (incrementalUpdate) {
            for (String eventId : eventHashCodes.keySet()) {
                if (!eventsToKeep.contains(eventId)) {
                    buildDeleteOperation(eventId, list);
                    ++deletedEvents;
                }
            }
        }

        LOGD(TAG, "Events: " + (incrementalUpdate ? "INCREMENTAL" : "FULL") + " update. " +
                updatedEvents + " to update, " + deletedEvents + " to delete. New total: " +
                mEvents.size());
    }

    private void buildDeleteOperation(String eventId, List<ContentProviderOperation> list) {
        Uri eventUri = ScheduleContract.addCallerIsSyncAdapterParameter(
                ScheduleContract.Events.buildEventUri(eventId));
        list.add(ContentProviderOperation.newDelete(eventUri).build());
    }

    private HashMap<String, String> loadEventHashCodes() {
        Uri uri = ScheduleContract.addCallerIsSyncAdapterParameter(
                ScheduleContract.Events.CONTENT_URI);
        LOGD(TAG, "Loading event hashcodes for event import optimization.");
        Cursor cursor = mContext.getContentResolver().query(uri, EventHashcodeQuery.PROJECTION,
                null, null, null);
        if (cursor == null || cursor.getCount() < 1) {
            LOGW(TAG, "Warning: failed to load event hashcodes. Not optimizing event import.");
            if (cursor != null) {
                cursor.close();
            }
            return null;
        }
        HashMap<String, String> hashcodeMap = new HashMap<String, String>();
        while (cursor.moveToNext()) {
            String eventId = cursor.getString(EventHashcodeQuery.EVENT_ID);
            String hashcode = cursor.getString(EventHashcodeQuery.EVENT_IMPORT_HASHCODE);
            hashcodeMap.put(eventId, hashcode == null ? "" : hashcode);
        }
        LOGD(TAG, "Event hashcodes loaded for " + hashcodeMap.size() + " events.");
        cursor.close();
        return hashcodeMap;
    }

    StringBuilder mStringBuilder = new StringBuilder();

    private void buildEvent(boolean isInsert,
                              Event event, ArrayList<ContentProviderOperation> list) {
        ContentProviderOperation.Builder builder;
        Uri allEventsUri = ScheduleContract
                .addCallerIsSyncAdapterParameter(ScheduleContract.Events.CONTENT_URI);
        Uri thisEventUri = ScheduleContract
                .addCallerIsSyncAdapterParameter(ScheduleContract.Events.buildEventUri(
                        event.id));

        if (isInsert) {
            builder = ContentProviderOperation.newInsert(allEventsUri);
        } else {
            builder = ContentProviderOperation.newUpdate(thisEventUri);
        }

        int color = mDefaultEventColor;
        try {
            if (!TextUtils.isEmpty(event.color)) {
                color = Color.parseColor(event.color);
            }
        } catch (IllegalArgumentException ex) {
            LOGD(TAG, "Ignoring invalid formatted event color: "+event.color);
        }
        builder.withValue(ScheduleContract.SyncColumns.UPDATED, System.currentTimeMillis())
                .withValue(ScheduleContract.Events.EVENT_ID, event.id)
                .withValue(ScheduleContract.Events.EVENT_LEVEL, null)            // Not available
                .withValue(ScheduleContract.Events.EVENT_TITLE, event.title)
                .withValue(ScheduleContract.Events.EVENT_ABSTRACT, event.description)
                .withValue(ScheduleContract.Events.EVENT_HASHTAG, event.hashtag)
                .withValue(ScheduleContract.Events.EVENT_YEAR, event.year)
                //.withValue(ScheduleContract.Events.EVENT_START, TimeUtils.timestampToMillis(event.startTimestamp, 0))
                //.withValue(ScheduleContract.Events.EVENT_END, TimeUtils.timestampToMillis(event.endTimestamp, 0))
                .withValue(ScheduleContract.Events.EVENT_TAGS, event.makeCommaList(event.tags))
                        // Note: we store this comma-separated list of tags IN ADDITION
                        // to storing the tags in proper relational format (in the events_tags
                        // relationship table). This is because when querying for events,
                        // we don't want to incur the performance penalty of having to do a
                        // subquery for every record to figure out the list of tags of each event.
                //.withValue(ScheduleContract.Events.EVENT_SPEAKER_NAMES, speakerNames)   //Deprecated
                        // Note: we store the human-readable list of speakers (which is redundant
                        // with the sessions_speakers relationship table) so that we can
                        // display it easily in lists without having to make an additional DB query
                        // (or another join) for each record.
                .withValue(ScheduleContract.Events.EVENT_DAYS, event.makeCommaList(event.days))
                        // Note: we store this comma-separated list of days IN ADDITION
                        // to storing the days in proper relational format (in the events_days
                        // relationship table). This is because when querying for events,
                        // we don't want to incur the performance penalty of having to do a
                        // subquery for every record to figure out the list of days of each event.
                .withValue(ScheduleContract.Events.EVENT_KEYWORDS, null)             // Not available
                .withValue(ScheduleContract.Events.EVENT_URL, event.url)
                .withValue(ScheduleContract.Events.EVENT_LIVESTREAM_URL,
                        event.isLivestream ? event.youtubeUrl : null)
                .withValue(ScheduleContract.Events.EVENT_MODERATOR_URL, null)    // Not available
                .withValue(ScheduleContract.Events.EVENT_REQUIREMENTS, null)     // Not available
                .withValue(ScheduleContract.Events.EVENT_YOUTUBE_URL,
                        event.isLivestream ? null : event.youtubeUrl)
                .withValue(ScheduleContract.Events.EVENT_PDF_URL, null)          // Not available
                .withValue(ScheduleContract.Events.EVENT_NOTES_URL, null)        // Not available
                .withValue(ScheduleContract.Events.EVENT_GROUPING_ORDER, event.groupingOrder)
                .withValue(ScheduleContract.Events.EVENT_IMPORT_HASHCODE,
                        event.getImportHashCode())
                .withValue(ScheduleContract.Events.EVENT_MAIN_TAG, event.mainTag)
                .withValue(ScheduleContract.Events.EVENT_CAPTIONS_URL, event.captionsUrl)
                .withValue(ScheduleContract.Events.EVENT_PHOTO_URL, event.photoUrl)
                .withValue(ScheduleContract.Events.EVENT_RELATED_CONTENT, event.relatedContent)
                .withValue(ScheduleContract.Events.EVENT_COLOR, color);
        list.add(builder.build());
    }

    // The type order of an event is the order# (in its category) of the tag that indicates
    // its type. So if we sort events by type order, they will be neatly grouped by type,
    // with the types appearing in the order given by the tag category that represents the
    // concept of event type.
    private int computeTypeOrder(Event event) {
        int order = Integer.MAX_VALUE;
        int keynoteOrder = -1;
        if (mTagMap == null) {
            throw new IllegalStateException("Attempt to compute type order without tag map.");
        }
        for (String tagId : event.tags) {
            if (Config.Tags.SPECIAL_KEYNOTE.equals(tagId)) {
                return keynoteOrder;
            }
            Tag tag = mTagMap.get(tagId);
            if (tag != null && Config.Tags.EVENT_GROUPING_TAG_CATEGORY.equals(tag.category)) {
                if (tag.order_in_category < order) {
                    order = tag.order_in_category;
                }
            }
        }
        return order;
    }

    private void buildEventVideosMapping(Event event,
                                         ArrayList<ContentProviderOperation> list) {
        final Uri uri = ScheduleContract.addCallerIsSyncAdapterParameter(
                ScheduleContract.Events.buildVideosDirUri(event.id));

        // delete any existing relationship between this event and videos
        list.add(ContentProviderOperation.newDelete(uri).build());

        // add relationship records to indicate the sessions for this event
        if (event.videos != null) {
            for (String videoId : event.videos) {
                list.add(ContentProviderOperation.newInsert(uri)
                        .withValue(ScheduleDatabase.EventsVideos.EVENT_ID, event.id)
                        .withValue(ScheduleDatabase.EventsVideos.VIDEO_ID, videoId)
                        .build());
            }
        }
    }

    private void buildTagsMapping(Event event, ArrayList<ContentProviderOperation> list) {
        final Uri uri = ScheduleContract.addCallerIsSyncAdapterParameter(
                ScheduleContract.Events.buildTagsDirUri(event.id));

        // delete any existing mappings
        list.add(ContentProviderOperation.newDelete(uri).build());

        // add a mapping (an event+tag tuple) for each tag in the event
        for (String tag : event.tags) {
            list.add(ContentProviderOperation.newInsert(uri)
                    .withValue(ScheduleDatabase.EventsTags.EVENT_ID, event.id)
                    .withValue(ScheduleDatabase.EventsTags.TAG_ID, tag).build());
        }
    }

    public void setTagMap(HashMap<String, Tag> tagMap) {
        mTagMap = tagMap;
    }

    public void setVideosMap(HashMap<String, Video> videoMap) {
        mVideoMap = videoMap;
    }
    /*public void setSpeakerMap(HashMap<String, Speaker> speakerMap) {
        mSpeakerMap = speakerMap;
    }*/

    private interface EventHashcodeQuery {
        String[] PROJECTION = {
                BaseColumns._ID,
                ScheduleContract.Events.EVENT_ID,
                ScheduleContract.Events.EVENT_IMPORT_HASHCODE
        };
        int _ID = 0;
        int EVENT_ID = 1;
        int EVENT_IMPORT_HASHCODE = 2;
    };
}
