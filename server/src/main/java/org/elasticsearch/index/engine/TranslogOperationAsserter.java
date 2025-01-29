/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.engine;

import org.apache.lucene.search.similarities.BM25Similarity;
import org.elasticsearch.index.cache.query.TrivialQueryCachingPolicy;
import org.elasticsearch.index.mapper.DocumentParser;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.translog.Translog;

import java.io.IOException;

/**
 *
 * A utility class to assert that translog operations with the same sequence number
 * in the same generation are either identical or equivalent when synthetic sources are used.
 */
public abstract class TranslogOperationAsserter {
    public static final TranslogOperationAsserter DEFAULT = new TranslogOperationAsserter() {
    };

    private TranslogOperationAsserter() {

    }

    public static TranslogOperationAsserter withEngineConfig(EngineConfig engineConfig) {
        return new TranslogOperationAsserter() {
            @Override
            public boolean assertSameIndexOperation(Translog.Index o1, Translog.Index o2) throws IOException {
                if (super.assertSameIndexOperation(o1, o2)) {
                    return true;
                }
                if (engineConfig.getIndexSettings().isRecoverySourceSyntheticEnabled()
                    || engineConfig.getMapperService().mappingLookup().inferenceFields().isEmpty() == false) {
                    return super.assertSameIndexOperation(synthesizeSource(engineConfig, o1), o2)
                        || super.assertSameIndexOperation(o1, synthesizeSource(engineConfig, o2));
                }
                return false;
            }
        };
    }

    static Translog.Index synthesizeSource(EngineConfig engineConfig, Translog.Index op) throws IOException {
        final ShardId shardId = engineConfig.getShardId();
        final MappingLookup mappingLookup = engineConfig.getMapperService().mappingLookup();
        final DocumentParser documentParser = engineConfig.getMapperService().documentParser();
        try (var reader = TranslogDirectoryReader.create(shardId, op, mappingLookup, documentParser, engineConfig, () -> {}, true)) {
            final Engine.Searcher searcher = new Engine.Searcher(
                "assert_translog",
                reader,
                new BM25Similarity(),
                null,
                TrivialQueryCachingPolicy.NEVER,
                () -> {}
            );
            try (var snapshot = newSnapshot(engineConfig, op, searcher);) {
                final Translog.Operation normalized = snapshot.next();
                assert normalized != null : "expected one operation; got zero";
                return (Translog.Index) normalized;
            }
        }
    }

    static Translog.Snapshot newSnapshot(EngineConfig engineConfig, Translog.Index op, Engine.Searcher searcher) throws IOException {
        if (engineConfig.getIndexSettings().isRecoverySourceSyntheticEnabled()) {
            return new LuceneSyntheticSourceChangesSnapshot(
                engineConfig.getMapperService(),
                searcher,
                LuceneSyntheticSourceChangesSnapshot.DEFAULT_BATCH_SIZE,
                Integer.MAX_VALUE,
                op.seqNo(),
                op.seqNo(),
                true,
                false,
                engineConfig.getIndexSettings().getIndexVersionCreated()
            );
        } else {
            return new LuceneChangesSnapshot(
                engineConfig.getMapperService(),
                searcher,
                LuceneSyntheticSourceChangesSnapshot.DEFAULT_BATCH_SIZE,
                op.seqNo(),
                op.seqNo(),
                true,
                false,
                false,
                engineConfig.getIndexSettings().getIndexVersionCreated()
            );
        }
    }

    public boolean assertSameIndexOperation(Translog.Index o1, Translog.Index o2) throws IOException {
        return Translog.Index.equalsWithoutAutoGeneratedTimestamp(o1, o2);
    }
}
