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

package org.apache.hadoop.yarn.api.records;

import java.io.IOException;
import java.text.NumberFormat;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.util.Records;

/**
 * <p>
 * {@link ReservationId} represents the <em>globally unique</em> identifier for
 * a reservation.
 * </p>
 *
 * <p>
 * The globally unique nature of the identifier is achieved by using the
 * <em>cluster timestamp</em> i.e. start-time of the {@code ResourceManager}
 * along with a monotonically increasing counter for the reservation.
 * </p>
 */
@Public
@Unstable
public abstract class ReservationId implements Comparable<ReservationId> {

    @Private
    @Unstable
    public static final String reserveIdStrPrefix = "reservation_";
    protected long clusterTimestamp;
    protected long id;

    @Private
    @Unstable
    public static ReservationId newInstance(long clusterTimestamp, long id) {
        ReservationId reservationId = Records.newRecord(ReservationId.class);
        reservationId.setClusterTimestamp(clusterTimestamp);
        reservationId.setId(id);
        reservationId.build();
        return reservationId;
    }

    /**
     * Get the long identifier of the {@link ReservationId} which is unique for
     * all Reservations started by a particular instance of the
     * {@code ResourceManager}.
     *
     * @return long identifier of the {@link ReservationId}
     */
    @Public
    @Unstable
    public abstract long getId();

    @Private
    @Unstable
    protected abstract void setId(long id);

    /**
     * Get the <em>start time</em> of the {@code ResourceManager} which is used to
     * generate globally unique {@link ReservationId}.
     *
     * @return <em>start time</em> of the {@code ResourceManager}
     */
    @Public
    @Unstable
    public abstract long getClusterTimestamp();

    @Private
    @Unstable
    protected abstract void setClusterTimestamp(long clusterTimestamp);

    protected abstract void build();

    static final ThreadLocal<NumberFormat> reservIdFormat =
    new ThreadLocal<NumberFormat>() {
        @Override
        public NumberFormat initialValue() {
            NumberFormat fmt = NumberFormat.getInstance();
            fmt.setGroupingUsed(false);
            fmt.setMinimumIntegerDigits(4);
            return fmt;
        }
    };

    @Override
    public int compareTo(ReservationId other) {
        if (this.getClusterTimestamp() - other.getClusterTimestamp() == 0) {
            return getId() > getId() ? 1 : getId() < getId() ? -1 : 0;
        } else {
            return this.getClusterTimestamp() > other.getClusterTimestamp() ? 1
                   : this.getClusterTimestamp() < other.getClusterTimestamp() ? -1 : 0;
        }
    }

    @Override
    public String toString() {
        return reserveIdStrPrefix + this.getClusterTimestamp() + "_"
               + reservIdFormat.get().format(getId());
    }

    /**
     * Parse the string argument as a {@link ReservationId}
     *
     * @param reservationId the string representation of the {@link ReservationId}
     * @return the {@link ReservationId} corresponding to the input string if
     *         valid, null if input is null
     * @throws IOException if unable to parse the input string
     */
    @Public
    @Unstable
    public static ReservationId parseReservationId(String reservationId)
    throws IOException {
        if (reservationId == null) {
            return null;
        }
        if (!reservationId.startsWith(reserveIdStrPrefix)) {
            throw new IOException("The specified reservation id is invalid: "
                                  + reservationId);
        }
        String[] resFields = reservationId.split("_");
        if (resFields.length != 3) {
            throw new IOException("The specified reservation id is not parseable: "
                                  + reservationId);
        }
        return newInstance(Long.parseLong(resFields[1]),
                           Long.parseLong(resFields[2]));
    }

    @Override
    public int hashCode() {
        // generated by eclipse
        final int prime = 31;
        int result = 1;
        result =
            prime * result
            + (int) (getClusterTimestamp() ^ (getClusterTimestamp() >>> 32));
        result = prime * result + (int) (getId() ^ (getId() >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        // generated by eclipse
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ReservationId other = (ReservationId) obj;
        if (getClusterTimestamp() != other.getClusterTimestamp())
            return false;
        if (getId() != other.getId())
            return false;
        return true;
    }

}
