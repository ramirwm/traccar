/*
 * Copyright 2016 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.traccar.Config;
import org.traccar.Context;
import org.traccar.helper.Log;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Position;

public class DeviceManager implements IdentityManager {

    public static final long DEFAULT_REFRESH_DELAY = 300;

    private final Config config;
    private final DataManager dataManager;
    private final long dataRefreshDelay;

    private Map<Long, Device> devicesById;
    private Map<String, Device> devicesByUniqueId;
    private AtomicLong devicesLastUpdate = new AtomicLong();

    private Map<Long, Group> groupsById;
    private AtomicLong groupsLastUpdate = new AtomicLong();

    private final Map<Long, Position> positions = new ConcurrentHashMap<>();

    public DeviceManager(DataManager dataManager) {
        this.dataManager = dataManager;
        this.config = Context.getConfig();
        dataRefreshDelay = config.getLong("database.refreshDelay", DEFAULT_REFRESH_DELAY) * 1000;
        if (dataManager != null) {
            try {
                updateGroupCache(true);
                updateDeviceCache(true);
                for (Position position : dataManager.getLatestPositions()) {
                    positions.put(position.getDeviceId(), position);
                }
            } catch (SQLException error) {
                Log.warning(error);
            }
        }
    }

    private void updateDeviceCache(boolean force) throws SQLException {

        long lastUpdate = devicesLastUpdate.get();
        if ((force || System.currentTimeMillis() - lastUpdate > dataRefreshDelay)
                && devicesLastUpdate.compareAndSet(lastUpdate, System.currentTimeMillis())) {
            GeofenceManager geofenceManager = Context.getGeofenceManager();
            Collection<Device> databaseDevices = dataManager.getAllDevices();
            if (devicesById == null) {
                devicesById = new ConcurrentHashMap<>(databaseDevices.size());
            }
            if (devicesByUniqueId == null) {
                devicesByUniqueId = new ConcurrentHashMap<>(databaseDevices.size());
            }
            Set<Long> databaseDevicesIds = new HashSet<>();
            Set<String> databaseDevicesUniqueIds = new HashSet<>();
            for (Device device : databaseDevices) {
                databaseDevicesIds.add(device.getId());
                databaseDevicesUniqueIds.add(device.getUniqueId());
                if (devicesById.containsKey(device.getId())) {
                    Device cachedDevice = devicesById.get(device.getId());
                    cachedDevice.setName(device.getName());
                    cachedDevice.setGroupId(device.getGroupId());
                    cachedDevice.setAttributes(device.getAttributes());
                    if (!device.getUniqueId().equals(cachedDevice.getUniqueId())) {
                        devicesByUniqueId.remove(cachedDevice.getUniqueId());
                        devicesByUniqueId.put(device.getUniqueId(), cachedDevice);
                    }
                    cachedDevice.setUniqueId(device.getUniqueId());
                } else {
                    devicesById.put(device.getId(), device);
                    devicesByUniqueId.put(device.getUniqueId(), device);
                    if (geofenceManager != null) {
                        Position lastPosition = getLastPosition(device.getId());
                        if (lastPosition != null) {
                            device.setGeofenceIds(geofenceManager.getCurrentDeviceGeofences(lastPosition));
                        }
                    }
                    device.setStatus(Device.STATUS_OFFLINE);
                }
            }
            for (Long cachedDeviceId : devicesById.keySet()) {
                if (!databaseDevicesIds.contains(cachedDeviceId)) {
                    devicesById.remove(cachedDeviceId);
                }
            }
            for (String cachedDeviceUniqId : devicesByUniqueId.keySet()) {
                if (!databaseDevicesUniqueIds.contains(cachedDeviceUniqId)) {
                    devicesByUniqueId.remove(cachedDeviceUniqId);
                }
            }
            databaseDevicesIds.clear();
            databaseDevicesUniqueIds.clear();
        }
    }

    @Override
    public Device getDeviceById(long id) {
        return devicesById.get(id);
    }

    @Override
    public Device getDeviceByUniqueId(String uniqueId) throws SQLException {
        boolean forceUpdate = !devicesByUniqueId.containsKey(uniqueId) && !config.getBoolean("database.ignoreUnknown");

        updateDeviceCache(forceUpdate);

        return devicesByUniqueId.get(uniqueId);
    }

    public Collection<Device> getAllDevices() {
        boolean forceUpdate = devicesById.isEmpty();

        try {
            updateDeviceCache(forceUpdate);
        } catch (SQLException e) {
            Log.warning(e);
        }

        return devicesById.values();
    }

    public Collection<Device> getDevices(long userId) throws SQLException {
        Collection<Device> devices = new ArrayList<>();
        for (long id : Context.getPermissionsManager().getDevicePermissions(userId)) {
            devices.add(devicesById.get(id));
        }
        return devices;
    }

    public void addDevice(Device device) throws SQLException {
        dataManager.addDevice(device);

        devicesById.put(device.getId(), device);
        devicesByUniqueId.put(device.getUniqueId(), device);
    }

    public void updateDevice(Device device) throws SQLException {
        dataManager.updateDevice(device);

        devicesById.put(device.getId(), device);
        devicesByUniqueId.put(device.getUniqueId(), device);
    }

    public void updateDeviceStatus(Device device) throws SQLException {
        dataManager.updateDeviceStatus(device);
        if (devicesById.containsKey(device.getId())) {
            Device cachedDevice = devicesById.get(device.getId());
            cachedDevice.setStatus(device.getStatus());
        }
    }

    public void removeDevice(long deviceId) throws SQLException {
        dataManager.removeDevice(deviceId);

        if (devicesById.containsKey(deviceId)) {
            String deviceUniqueId = devicesById.get(deviceId).getUniqueId();
            devicesById.remove(deviceId);
            devicesByUniqueId.remove(deviceUniqueId);
        }
        positions.remove(deviceId);
    }

    public boolean isLatestPosition(Position position) {
        Position lastPosition = getLastPosition(position.getDeviceId());
        return lastPosition == null || position.getFixTime().compareTo(lastPosition.getFixTime()) > 0;
    }

    public void updateLatestPosition(Position position) throws SQLException {

        if (isLatestPosition(position)) {

            dataManager.updateLatestPosition(position);

            if (devicesById.containsKey(position.getDeviceId())) {
                devicesById.get(position.getDeviceId()).setPositionId(position.getId());
            }

            positions.put(position.getDeviceId(), position);

            if (Context.getConnectionManager() != null) {
                Context.getConnectionManager().updatePosition(position);
            }
        }
    }

    @Override
    public Position getLastPosition(long deviceId) {
        return positions.get(deviceId);
    }

    public Collection<Position> getInitialState(long userId) {

        List<Position> result = new LinkedList<>();

        if (Context.getPermissionsManager() != null) {
            for (long deviceId : Context.getPermissionsManager().getDevicePermissions(userId)) {
                if (positions.containsKey(deviceId)) {
                    result.add(positions.get(deviceId));
                }
            }
        }

        return result;
    }

    private void updateGroupCache(boolean force) throws SQLException {

        long lastUpdate = groupsLastUpdate.get();
        if ((force || System.currentTimeMillis() - lastUpdate > dataRefreshDelay)
                && groupsLastUpdate.compareAndSet(lastUpdate, System.currentTimeMillis())) {
            Collection<Group> databaseGroups = dataManager.getAllGroups();
            if (groupsById == null) {
                groupsById = new ConcurrentHashMap<>(databaseGroups.size());
            }
            Set<Long> databaseGroupsIds = new HashSet<>();
            for (Group group : databaseGroups) {
                databaseGroupsIds.add(group.getId());
                if (groupsById.containsKey(group.getId())) {
                    Group cachedGroup = groupsById.get(group.getId());
                    cachedGroup.setName(group.getName());
                    cachedGroup.setGroupId(group.getGroupId());
                } else {
                    groupsById.put(group.getId(), group);
                }
            }
            for (Long cachedGroupId : groupsById.keySet()) {
                if (!databaseGroupsIds.contains(cachedGroupId)) {
                    devicesById.remove(cachedGroupId);
                }
            }
            databaseGroupsIds.clear();
        }
    }

    public Group getGroupById(long id) {
        return groupsById.get(id);
    }

    public Collection<Group> getAllGroups() {
        boolean forceUpdate = groupsById.isEmpty();

        try {
            updateGroupCache(forceUpdate);
        } catch (SQLException e) {
            Log.warning(e);
        }

        return groupsById.values();
    }

    public Collection<Group> getGroups(long userId) throws SQLException {
        Collection<Group> groups = new ArrayList<>();
        for (long id : Context.getPermissionsManager().getGroupPermissions(userId)) {
            groups.add(getGroupById(id));
        }
        return groups;
    }

    private void checkGroupCycles(Group group) {
        Set<Long> groups = new HashSet<>();
        while (group != null) {
            if (groups.contains(group.getId())) {
                throw new IllegalArgumentException("Cycle in group hierarchy");
            }
            groups.add(group.getId());
            group = groupsById.get(group.getGroupId());
        }
    }

    public void addGroup(Group group) throws SQLException {
        checkGroupCycles(group);
        dataManager.addGroup(group);
        groupsById.put(group.getId(), group);
    }

    public void updateGroup(Group group) throws SQLException {
        checkGroupCycles(group);
        dataManager.updateGroup(group);
        groupsById.put(group.getId(), group);
    }

    public void removeGroup(long groupId) throws SQLException {
        dataManager.removeGroup(groupId);
        groupsById.remove(groupId);
    }
}
