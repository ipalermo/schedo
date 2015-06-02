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

package com.ncode.android.apps.schedo.model;

import android.content.Context;
import android.content.CursorLoader;
import android.database.Cursor;
import android.provider.BaseColumns;
import android.text.TextUtils;

import com.ncode.android.apps.schedo.Config;
import com.ncode.android.apps.schedo.provider.ScheduleContract;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class MyScheduleMetadata {

    // hash map from event ID to MySchedEvent
    HashMap<String, MySchedEvent> mEventSchedsById = new HashMap<String, MySchedEvent>();

    public static CursorLoader createCursorMySchedLoader(Context context) {
        return new CursorLoader(context, ScheduleContract.MySchedule.CONTENT_URI, MyScheduleQuery.PROJECTION,
                null, null, null);
    }

    public MyScheduleMetadata(Cursor cursor) {
        while (cursor.moveToNext()) {
            MySchedEvent mySchedEvent = new MySchedEvent(cursor.getString(MyScheduleQuery.SESSION_ID),
                    "evente title",//cursor.getString(MyScheduleQuery.MY_SCHEDULE_ACCOUNT_NAME),
                    "2015",//cursor.getString(MyScheduleQuery),
                    "tag",//cursor.getInt(MyScheduleQuery.),
                    0);//cursor.getString(MyScheduleQuery),
                    //cursor.getInt(MyScheduleQuery.TAG_COLOR));
            mEventSchedsById.put(mySchedEvent.getId(), mySchedEvent);
        }
    }

    public MySchedEvent getSchedule(String scheduleId) {
        return mEventSchedsById.containsKey(scheduleId) ? mEventSchedsById.get(scheduleId) : null;
    }

    public Collection<MySchedEvent> getMyScheduleEvents() {
        return Collections.unmodifiableCollection(mEventSchedsById.values());
    }


    private interface MyScheduleQuery {
        int _TOKEN = 0x1;
        /*
        SELECT DISTINCT e.event_title FROM events e WHERE e.event_id IN (
SELECT s.event_id FROM sessions s WHERE s.session_id IN (
SELECT m.session_id FROM myschedule m where m.account_name = 'fer.palermo86@gmail.com'))
        SELECT e._id, e.event_id, e.event_title, e.event_year, e.event_days
        FROM events e
          INNER JOIN sessions s
             ON e.event_id = s.event_id
          INNER JOIN myschedule m
             ON m.session_id = s.session_id
        WHERE
	        m.account_name = 'fer.palermo86@gmail.com'
         */
        String[] PROJECTION = {
                BaseColumns._ID,
                ScheduleContract.MySchedule.SESSION_ID,
                ScheduleContract.MySchedule.MY_SCHEDULE_ACCOUNT_NAME,
                ScheduleContract.MySchedule.MY_SCHEDULE_DIRTY_FLAG,
                ScheduleContract.MySchedule.CONTENT_TYPE,
                ScheduleContract.MySchedule.CONTENT_ITEM_TYPE,
                ScheduleContract.MySchedule.MY_SCHEDULE_IN_SCHEDULE
        };

        int _ID = 0;
        int SESSION_ID = 1;
        int MY_SCHEDULE_ACCOUNT_NAME = 2;
        int MY_SCHEDULE_DIRTY_FLAG = 3;
        int CONTENT_TYPE = 4;
        int CONTENT_ITEM_TYPE = 5;
        int MY_SCHEDULE_IN_SCHEDULE = 6;
    }

    static public class MySchedEvent implements Comparable<MySchedEvent> {
        private String mId;
        private String mTitle;
        private String mYear;
        private String mMainTag;
        private int mColor;

        public MySchedEvent(String id, String title, String year, String mainTag, int color) {
            mId = id;
            mTitle = title;
            mYear = year;
            mMainTag = mainTag;
            mColor = color;
        }


        @Override
        public int compareTo(MySchedEvent another) {
            return mTitle.compareToIgnoreCase(another.mTitle) ;
        }

        public String getId() {
            return mId;
        }

        public String getTitle() {
            return mTitle;
        }

        public String getYear() {
            return mYear;
        }

        public String getMainTag() {
            return mMainTag;
        }

        public int getColor() {
            return mColor;
        }
    }
}
