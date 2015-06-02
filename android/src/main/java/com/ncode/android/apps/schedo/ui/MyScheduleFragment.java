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
import android.app.LoaderManager;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.app.ListFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.ncode.android.apps.schedo.R;
import com.ncode.android.apps.schedo.model.MyScheduleMetadata;
import com.ncode.android.apps.schedo.model.TagMetadata;
import com.ncode.android.apps.schedo.provider.ScheduleContract;
import com.ncode.android.apps.schedo.util.UIUtils;

import static com.ncode.android.apps.schedo.util.LogUtils.LOGD;
import static com.ncode.android.apps.schedo.util.LogUtils.makeLogTag;

/**
 * A list fragment that shows items from MySchedule.
 * To use, call setListAdapter(), passing it an instance of your MyScheduleAdapter.
 */
public class MyScheduleFragment extends ListFragment implements LoaderManager.LoaderCallbacks<Cursor> {
    private String mContentDescription = null;
    private View mRoot = null;
    private static final String TAG = makeLogTag(MyScheduleFragment.class);
    private MyScheduleCallbacks mCallbacks = sDummyCallbacks;
    private static final int MYSCHEDULE_METADATA_TOKEN = 0x4;
    private MyScheduleMetadata mScheduleMetadata = null;

    public interface MyScheduleCallbacks {
        public void onEventSchedSelected(String eventId, View clickedView);
        public void onEventSchedMetadataLoaded(MyScheduleMetadata metadata);
    }

    private static MyScheduleCallbacks sDummyCallbacks = new MyScheduleCallbacks() {
        @Override
        public void onEventSchedSelected(String eventId, View clickedView) {}

        @Override
        public void onEventSchedMetadataLoaded(MyScheduleMetadata metadata) {}
    };

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle data) {
        LOGD(TAG, "onCreateLoader, id=" + id + ", data=" + data);
        final Intent intent = BaseActivity.fragmentArgumentsToIntent(data);
        Uri eventsUri = intent.getData();

        Loader<Cursor> loader = null;
        if (id == MYSCHEDULE_METADATA_TOKEN) {
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
        LOGD(TAG, "Loader finished: "  + (token == MYSCHEDULE_METADATA_TOKEN ? "tags" :
                        "unknown"));
        if (token == MYSCHEDULE_METADATA_TOKEN) {
            mScheduleMetadata = new MyScheduleMetadata(cursor);
            cursor.close();
            //updateCollectionView();
            mCallbacks.onEventSchedMetadataLoaded(mScheduleMetadata);
        } else {
            LOGD(TAG, "Query complete, Not Actionable: " + token);
            cursor.close();
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {

    }

    public interface Listener {
        public void onFragmentViewCreated(ListFragment fragment);
        public void onFragmentAttached(MyScheduleFragment fragment);
        public void onFragmentDetached(MyScheduleFragment fragment);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = inflater.inflate(R.layout.fragment_my_schedule, container, false);
        if (mContentDescription != null) {
            mRoot.setContentDescription(mContentDescription);
        }
        return mRoot;
    }

    public void setContentDescription(String desc) {
        mContentDescription = desc;
        if (mRoot != null) {
            mRoot.setContentDescription(mContentDescription);
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getActivity() instanceof Listener) {
            ((Listener) getActivity()).onFragmentViewCreated(this);
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (getActivity() instanceof Listener) {
            ((Listener) getActivity()).onFragmentAttached(this);
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (getActivity() instanceof Listener) {
            ((Listener) getActivity()).onFragmentDetached(this);
        }
    }
}
