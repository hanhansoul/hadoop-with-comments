/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapreduce.lib.input;

import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

/**
 * Input format that is a <code>CombineFileInputFormat</code>-equivalent for
 * <code>SequenceFileInputFormat</code>.
 *
 * @see CombineFileInputFormat
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class CombineSequenceFileInputFormat<K,V>
    extends CombineFileInputFormat<K,V> {
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public RecordReader<K,V> createRecordReader(InputSplit split,
            TaskAttemptContext context) throws IOException {
        return new CombineFileRecordReader((CombineFileSplit)split, context,
                                           SequenceFileRecordReaderWrapper.class);
    }

    /**
     * A record reader that may be passed to <code>CombineFileRecordReader</code>
     * so that it can be used in a <code>CombineFileInputFormat</code>-equivalent
     * for <code>SequenceFileInputFormat</code>.
     *
     * @see CombineFileRecordReader
     * @see CombineFileInputFormat
     * @see SequenceFileInputFormat
     */
    private static class SequenceFileRecordReaderWrapper<K,V>
        extends CombineFileRecordReaderWrapper<K,V> {
        // this constructor signature is required by CombineFileRecordReader
        public SequenceFileRecordReaderWrapper(CombineFileSplit split,
                                               TaskAttemptContext context, Integer idx)
        throws IOException, InterruptedException {
            super(new SequenceFileInputFormat<K,V>(), split, context, idx);
        }
    }
}