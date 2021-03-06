/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.search.facet.datehistogram;

import org.apache.lucene.index.AtomicReaderContext;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.joda.TimeZoneRounding;
import org.elasticsearch.common.trove.ExtTLongObjectHashMap;
import org.elasticsearch.index.fielddata.DoubleValues;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.index.fielddata.LongValues;
import org.elasticsearch.search.facet.AbstractFacetCollector;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;

/**
 * A histogram facet collector that uses different fields for the key and the value.
 */
public class ValueDateHistogramFacetCollector extends AbstractFacetCollector {

    private final IndexNumericFieldData keyIndexFieldData;
    private final IndexNumericFieldData valueIndexFieldData;

    private final DateHistogramFacet.ComparatorType comparatorType;

    private LongValues keyValues;
    private final DateHistogramProc histoProc;

    public ValueDateHistogramFacetCollector(String facetName, IndexNumericFieldData keyIndexFieldData, IndexNumericFieldData valueIndexFieldData, TimeZoneRounding tzRounding, DateHistogramFacet.ComparatorType comparatorType, SearchContext context) {
        super(facetName);
        this.comparatorType = comparatorType;
        this.keyIndexFieldData = keyIndexFieldData;
        this.valueIndexFieldData = valueIndexFieldData;
        this.histoProc = new DateHistogramProc(tzRounding);
    }

    @Override
    protected void doCollect(int doc) throws IOException {
        keyValues.forEachValueInDoc(doc, histoProc);
    }

    @Override
    protected void doSetNextReader(AtomicReaderContext context) throws IOException {
        keyValues = keyIndexFieldData.load(context).getLongValues();
        histoProc.valueValues = valueIndexFieldData.load(context).getDoubleValues();
    }

    @Override
    public Facet facet() {
        return new InternalFullDateHistogramFacet(facetName, comparatorType, histoProc.entries, true);
    }

    public static class DateHistogramProc implements LongValues.ValueInDocProc {

        final ExtTLongObjectHashMap<InternalFullDateHistogramFacet.FullEntry> entries = CacheRecycler.popLongObjectMap();

        private final TimeZoneRounding tzRounding;

        DoubleValues valueValues;

        final ValueAggregator valueAggregator = new ValueAggregator();

        public DateHistogramProc(TimeZoneRounding tzRounding) {
            this.tzRounding = tzRounding;
        }

        @Override
        public void onMissing(int docId) {
        }

        @Override
        public void onValue(int docId, long value) {
            long time = tzRounding.calc(value);

            InternalFullDateHistogramFacet.FullEntry entry = entries.get(time);
            if (entry == null) {
                entry = new InternalFullDateHistogramFacet.FullEntry(time, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, 0);
                entries.put(time, entry);
            }
            entry.count++;
            valueAggregator.entry = entry;
            valueValues.forEachValueInDoc(docId, valueAggregator);
        }

        public static class ValueAggregator implements DoubleValues.ValueInDocProc {

            InternalFullDateHistogramFacet.FullEntry entry;

            @Override
            public void onMissing(int docId) {
            }

            @Override
            public void onValue(int docId, double value) {
                entry.totalCount++;
                entry.total += value;
                if (value < entry.min) {
                    entry.min = value;
                }
                if (value > entry.max) {
                    entry.max = value;
                }
            }
        }
    }
}