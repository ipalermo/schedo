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

package com.ncode.android.apps.schedo.io.model;

import com.ncode.android.apps.schedo.util.HashUtils;

public class Event {
    public String id;
    public String url;
    public String description;
    public String title;
    public Day[] days;
    public String[] tags;
    public String[] videos;
    public String year;
    public String eventTimezoneId;
    public String youtubeUrl;
    public String[] sessions;
    public String hashtag;
    public String subtype;
    public String captionsUrl;
    public String photoUrl;
    public boolean isLivestream;
    public String mainTag;
    public String color;
    public String relatedContent;
    public int groupingOrder;

    public String getImportHashCode() {
        StringBuilder sb = new StringBuilder();
        sb.append("id").append(id == null ? "" : id)
                .append("description").append(description == null ? "" : description)
                .append("title").append(title == null ? "" : title)
                .append("url").append(url == null ? "" : url)
                .append("eventYear").append(year == null ? "" : year)
                .append("eventTimezoneId").append(eventTimezoneId == null ? "" : eventTimezoneId)
                .append("youtubeUrl").append(youtubeUrl == null ? "" : youtubeUrl)
                .append("subtype").append(subtype == null ? "" : subtype)
                .append("hashtag").append(hashtag == null ? "" : hashtag)
                .append("isLivestream").append(isLivestream ? "true" : "false")
                .append("mainTag").append(mainTag)
                .append("captionsUrl").append(captionsUrl)
                .append("photoUrl").append(photoUrl)
                .append("relatedContent").append(relatedContent)
                .append("color").append(color)
                .append("groupingOrder").append(groupingOrder);
        for (String tag : tags) {
            sb.append("tag").append(tag);
        }
        if (sessions!=null)
            for (String session : sessions) {
                sb.append("session").append(session == null ? "" : session);
            }
        for (Day day : days) {
            sb.append("day").append(day.getStartTimestamp());
            sb.append("day").append(day.getEndTimestamp());
        }
        if (videos!=null)
            for (String video : videos)
                sb.append("video").append(video);

        return HashUtils.computeWeakHash(sb.toString());
    }

    public String makeCommaList(String[] list) {
        int i;
        if (list.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(list[0]);
        for (i = 1; i < list.length; i++) {
            sb.append(",").append(list[i]);
        }
        return sb.toString();
    }

    public boolean hasTag(String tag) {
        for (String myTag : tags) {
            if (myTag.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    public String getDays(Day[] days){
        String[] daysList = new String[days.length*2];
        for (int i=0,j = 0; i < days.length; i++,j+=2) {
            daysList[j] = days[i].getStartTimestamp();
            daysList[j+1] = days[i].getEndTimestamp();
        }
        return makeCommaList(daysList);
    }
}


