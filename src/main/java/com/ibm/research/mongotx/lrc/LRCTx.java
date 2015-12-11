/*
 * Copyright IBM Corp. 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ibm.research.mongotx.lrc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.bson.Document;

import com.ibm.research.mongotx.Tx;
import com.ibm.research.mongotx.TxRollback;

class LRCTx implements Tx, Constants {

    enum STATE {
        READING, WRITING, COMMITTED, ABORTED, UNKNOWN
    };

    final LatestReadCommittedTxDB txDB;
    final String txId;
    final long started = System.currentTimeMillis();
    STATE state = STATE.READING;
    final Map<LRCTxDBCollection, Map<Object, Document>> dirtyMaps = new HashMap<>();
    final Map<LRCTxDBCollection, Map<Object, Document>> cacheMaps = new HashMap<>();
    final Map<LRCTxDBCollection, Set<Object>> pinnedKeySets = new HashMap<>();

    public LRCTx(LatestReadCommittedTxDB txDB, String txId) {
        this.txDB = txDB;
        this.txId = txId;
    }

    void insertTxStateIfNecessary() throws TxRollback {
        if (state != STATE.WRITING && state != STATE.READING)
            throw new IllegalStateException("state is " + state);

        if (state == STATE.WRITING)
            return;

        Document txState = new Document(ATTR_ID, txId)//
                .append(ATTR_TX_STATE, ATTR_TX_VALUE_ACTIVE)//
                .append(ATTR_TX_TIMEOUT, started + TX_TIMEOUT);

        txDB.sysCol.insertOne(txState);

        state = STATE.WRITING;
    }

    void putDirty(LRCTxDBCollection col, Object key, Document dirty) {
        Map<Object, Document> dirtyMap = dirtyMaps.get(col);
        if (dirtyMap == null) {
            dirtyMap = new HashMap<>();
            dirtyMaps.put(col, dirtyMap);
        }
        dirtyMap.put(key, dirty);

        putCache(col, key, dirty, true);

    }

    Document getDirty(LRCTxDBCollection col, Object key) {
        Map<Object, Document> dirtyMap = dirtyMaps.get(col);
        if (dirtyMap == null)
            return null;
        return dirtyMap.get(key);
    }

    void putCache(LRCTxDBCollection col, Object key, Document latest, boolean pin) {
        Map<Object, Document> cacheMap = cacheMaps.get(col);
        if (cacheMap == null) {
            cacheMap = new HashMap<>();
            cacheMaps.put(col, cacheMap);
        }

        cacheMap.put(key, latest);

        if (pin) {
            Set<Object> pinnedKeySet = pinnedKeySets.get(col);
            if (pinnedKeySet == null) {
                pinnedKeySet = new HashSet<>();
                pinnedKeySets.put(col, pinnedKeySet);
            }
            pinnedKeySet.add(key);
        }
    }

    boolean isPinned(LRCTxDBCollection col, Object key) {
        Set<Object> pinnedKeySet = pinnedKeySets.get(col);
        if (pinnedKeySet == null)
            return false;
        else
            return pinnedKeySet.contains(key);
    }

    Document getCache(LRCTxDBCollection col, Object key) {
        Map<Object, Document> cacheMap = cacheMaps.get(col);
        if (cacheMap == null)
            return null;
        return cacheMap.get(key);
    }

    void committed() {
        state = STATE.COMMITTED;
    }

    void aborted() {
        state = STATE.ABORTED;
    }

    boolean isActive() {
        return state == STATE.READING || state == STATE.WRITING;
    }

    boolean isReadOnly() {
        return dirtyMaps.isEmpty();
    }

    boolean isCommitted() {
        return state == STATE.COMMITTED;
    }

    boolean isAborted() {
        return state == STATE.ABORTED;
    }

    boolean isFinished() {
        return state == STATE.COMMITTED || state == STATE.ABORTED;
    }

    boolean abort(String txId) {
        Document query = new Document(ATTR_ID, txId)//
                .append(ATTR_TX_TIMEOUT, new Document()//
                        .append("$lt", System.currentTimeMillis()));
        Document newTxState = new Document(ATTR_ID, txId)//
                .append(ATTR_TX_STATE, ATTR_TX_VALUE_ABORTED);

        if (txDB.sysCol.replaceOne(query, newTxState).getModifiedCount() == 1L)
            return true;

        Iterator<Document> itrLatestTxState = txDB.sysCol.find(new Document(ATTR_ID, txId)).iterator();
        if (!itrLatestTxState.hasNext())
            return false;
        return ATTR_TX_VALUE_ABORTED.equals(itrLatestTxState.next().get(ATTR_TX_STATE));
    }

    @Override
    public void commit() throws TxRollback {
        if (!isActive())
            throw new IllegalStateException("state is not active");

        try {
            if (dirtyMaps.isEmpty()) {
                committed();
                return;
            }

            boolean committed = true;
            Exception ex = null;
            if (!dirtyMaps.isEmpty()) {
                Document query = new Document()//
                        .append(ATTR_ID, txId)//
                        .append(ATTR_TX_STATE, ATTR_TX_VALUE_ACTIVE);

                Document newTxState = new Document()//
                        .append(ATTR_ID, txId)//
                        .append(ATTR_TX_STATE, ATTR_TX_VALUE_COMMITTED);

                try {
                    if (txDB.sysCol.replaceOne(query, newTxState).getModifiedCount() != 1L)
                        committed = false;

                } catch (Exception ex_) {
                    committed = false;
                    ex = ex_;
                }
            }

            if (!committed) {
                rollback();
                if (ex == null)
                    throw new TxRollback("commit error: state shift was falied");
                else
                    throw new TxRollback("commit error", ex);
            }

            committed();

            for (Map.Entry<LRCTxDBCollection, Map<Object, Document>> dirtyMapEntry : dirtyMaps.entrySet()) {
                LRCTxDBCollection col = dirtyMapEntry.getKey();
                Map<Object, Document> dirtyMap = dirtyMapEntry.getValue();
                for (Map.Entry<Object, Document> dirtyEntry : dirtyMap.entrySet()) {
                    Object key = dirtyEntry.getKey();
                    Document dirty = dirtyEntry.getValue();
                    col.commit(this, key, dirty);
                }
            }

            txDB.sysCol.deleteOne(new Document(ATTR_ID, txId));

        } finally {
            if (state == STATE.COMMITTED)
                txDB.finished(this);
        }
    }

    @Override
    public void rollback() {
        if (state == STATE.ABORTED)
            return;

        try {
            if (state == STATE.WRITING) {
                Document query = new Document()//
                        .append(ATTR_ID, txId)//
                        .append(ATTR_TX_STATE, ATTR_TX_VALUE_ACTIVE);

                Document newTxState = new Document()//
                        .append(ATTR_ID, txId)//
                        .append(ATTR_TX_STATE, ATTR_TX_VALUE_COMMITTED);

                txDB.sysCol.replaceOne(query, newTxState);

                for (Map.Entry<LRCTxDBCollection, Map<Object, Document>> dirtyMapEntry : dirtyMaps.entrySet()) {
                    LRCTxDBCollection col = dirtyMapEntry.getKey();
                    Map<Object, Document> dirtyMap = dirtyMapEntry.getValue();
                    for (Map.Entry<Object, Document> dirtyEntry : dirtyMap.entrySet()) {
                        Object key = dirtyEntry.getKey();
                        Document dirty = dirtyEntry.getValue();
                        col.rollback(this, key, ((Document) dirty.get(ATTR_VALUE_UNSAFE)).containsKey(ATTR_VALUE_UNSAFE_INSERT));
                    }
                }
                txDB.sysCol.deleteOne(new Document(ATTR_ID, txId));
            }
        } finally {
            txDB.finished(this);
        }
    }

}