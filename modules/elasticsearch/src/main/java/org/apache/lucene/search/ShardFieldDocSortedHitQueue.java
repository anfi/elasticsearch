/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.lucene.search;

import org.apache.lucene.util.PriorityQueue;
import org.elasticsearch.search.controller.ShardFieldDoc;

import java.text.Collator;
import java.util.Locale;

/**
 * @author kimchy (Shay Banon)
 */
// LUCENE TRACK, Had to copy over in order ot improve same order tie break to take shards into account
public class ShardFieldDocSortedHitQueue extends PriorityQueue<ShardFieldDoc> {

    volatile SortField[] fields = null;

    // used in the case where the fields are sorted by locale
    // based strings
    volatile Collator[] collators = null;


    /**
     * Creates a hit queue sorted by the given list of fields.
     *
     * @param fields Fieldable names, in priority order (highest priority first).
     * @param size   The number of hits to retain.  Must be greater than zero.
     */
    public ShardFieldDocSortedHitQueue(SortField[] fields, int size) {
        initialize(size);
        setFields(fields);
    }


    /**
     * Allows redefinition of sort fields if they are <code>null</code>.
     * This is to handle the case using ParallelMultiSearcher where the
     * original list contains AUTO and we don't know the actual sort
     * type until the values come back.  The fields can only be set once.
     * This method should be synchronized external like all other PQ methods.
     *
     * @param fields
     */
    public void setFields(SortField[] fields) {
        this.fields = fields;
        this.collators = hasCollators(fields);
    }


    /**
     * Returns the fields being used to sort.
     */
    SortField[] getFields() {
        return fields;
    }


    /**
     * Returns an array of collators, possibly <code>null</code>.  The collators
     * correspond to any SortFields which were given a specific locale.
     *
     * @param fields Array of sort fields.
     * @return Array, possibly <code>null</code>.
     */
    private Collator[] hasCollators(final SortField[] fields) {
        if (fields == null) return null;
        Collator[] ret = new Collator[fields.length];
        for (int i = 0; i < fields.length; ++i) {
            Locale locale = fields[i].getLocale();
            if (locale != null)
                ret[i] = Collator.getInstance(locale);
        }
        return ret;
    }


    /**
     * Returns whether <code>a</code> is less relevant than <code>b</code>.
     *
     * @param a ScoreDoc
     * @param b ScoreDoc
     * @return <code>true</code> if document <code>a</code> should be sorted after document <code>b</code>.
     */
    @SuppressWarnings("unchecked") @Override
    protected final boolean lessThan(final ShardFieldDoc docA, final ShardFieldDoc docB) {
        final int n = fields.length;
        int c = 0;
        for (int i = 0; i < n && c == 0; ++i) {
            final int type = fields[i].getType();
            if (type == SortField.STRING) {
                final String s1 = (String) docA.fields[i];
                final String s2 = (String) docB.fields[i];
                // null values need to be sorted first, because of how FieldCache.getStringIndex()
                // works - in that routine, any documents without a value in the given field are
                // put first.  If both are null, the next SortField is used
                if (s1 == null) {
                    c = (s2 == null) ? 0 : -1;
                } else if (s2 == null) {
                    c = 1;
                } else if (fields[i].getLocale() == null) {
                    c = s1.compareTo(s2);
                } else {
                    c = collators[i].compare(s1, s2);
                }
            } else {
                c = docA.fields[i].compareTo(docB.fields[i]);
                if (type == SortField.SCORE) {
                    c = -c;
                }
            }
            // reverse sort
            if (fields[i].getReverse()) {
                c = -c;
            }
        }

        // avoid random sort order that could lead to duplicates (bug #31241):
        if (c == 0) {
            // CHANGE: Add shard base tie breaking
            c = docA.shardTarget().compareTo(docB.shardTarget());
            if (c == 0) {
                return docA.doc > docB.doc;
            }
        }

        return c > 0;
    }
}
