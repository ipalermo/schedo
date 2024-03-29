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

public class Day {
    private String startTimestamp;
    private String endTimestamp;

    public String getImportHashcode() {
        StringBuilder sb = new StringBuilder();
        sb.append("startTimestamp").append(startTimestamp == null ? "" : startTimestamp)
                .append("endTimestamp").append(endTimestamp == null ? "" : endTimestamp);
        return HashUtils.computeWeakHash(sb.toString());
    }

    public String getStartTimestamp() {
        return startTimestamp;
    }

    public String getEndTimestamp() {
        return endTimestamp;
    }

    public void setStartTimestamp(String startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public void setEndTimestamp(String endTimestamp) {
        this.endTimestamp = endTimestamp;
    }
}


