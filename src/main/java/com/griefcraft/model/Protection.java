/*
 * Copyright 2011 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and contributors and should not be interpreted as representing official policies,
 * either expressed or implied, of anybody else.
 */

package com.griefcraft.model;

import com.griefcraft.lwc.LWC;
import com.griefcraft.scripting.event.LWCProtectionRemovePostEvent;
import com.griefcraft.util.Colors;
import com.griefcraft.util.StringUtil;
import com.griefcraft.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class Protection {

    /**
     * The protection type
     *
     * <p>Ordering <b>must NOT change</b> as ordinal values are used</p>
     */
    public enum Type {

        /**
         * The protection is usable by anyone; the most common use would include community chests
         * where anyone can use the chest but no one should be able to protect as their own.
         */
        PUBLIC,

        /**
         * The owner (and anyone else) must enter a set password entered onto the chest in order
         * to be able to access it. Entering the correct password allows them to use the chest
         * until they log out or the protection is removed.
         */
        PASSWORD,

        /**
         * The protection is only usable by the player who created it. Further access can be
         * given to players, groups, and even more specific entities
         * such as Towns in the "Towny" plugin, or access lists via the "Lists" plugin
         */
        PRIVATE,

        /**
         * Can only be created by LWC Admins. A kick reason is provided and the protection
         * then acts as a honeypot. If anyone attempts to access it, they are kicked with
         * the given reason.
         */
        TRAP_KICK,

        /**
         * Can only be created by LWC Admins. Same as TRAP_KICK, a ban reason is provided
         * at creation. Any users that access this protection is <b>LOCAL BANNED</b> via
         * MCBans. If the plugin is not on the server, they are not banned.
         */
        TRAP_BAN,

        /**
         * Allows players to deposit items into 
         */
        DONATION;

        /**
         * Match a protection type using its string form
         *
         * @param text
         * @return
         */
        public static Type matchType(String text) {
            for (Type type : values()) {
                if (type.toString().equalsIgnoreCase(text)) {
                    return type;
                }
            }

            throw new IllegalArgumentException("No Protection Type found for given type: " + text);
        }

    }

    /**
     * All of the history items associated with this protection
     */
    private final Set<History> historyCache = new HashSet<History>();

    /**
     * List of the accessRights rights for the protection
     */
    private final Set<AccessRight> accessRights = new HashSet<AccessRight>();

    /**
     * List of flags enabled on the protection
     */
    private final Set<Flag> flags = new HashSet<Flag>();

    /**
     * The block id
     */
    private int blockId;

    /**
     * The password for the chest
     */
    private String password;

    /**
     * JSON data for the protection
     */
    private final JSONObject data = new JSONObject();

    /**
     * Unique id (in sql)
     */
    private int id;

    /**
     * The owner of the chest
     */
    private String owner;

    /**
     * The protection type
     */
    private Type type;

    /**
     * The world this protection is in
     */
    private String world;

    /**
     * The x coordinate
     */
    private int x;

    /**
     * The y coordinate
     */
    private int y;

    /**
     * The z coordinate
     */
    private int z;

    /**
     * The timestamp of when the protection was last accessed
     */
    private long lastAccessed;

    /**
     * The time the protection was created
     */
    private String creation;

    /**
     * Immutable flag for the protection. When removed, this bool is switched to true and any setters
     * will no longer work. However, everything is still intact and in memory at this point (for now.)
     */
    private boolean removed = false;

    /**
     * If the protection is pending removal. Only used internally.
     */
    private boolean removing = false;

    /**
     * True when the protection has been modified and should be saved
     */
    private boolean modified = false;

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Protection)) {
            return false;
        }

        Protection other = (Protection) object;

        return id == other.id && x == other.x && y == other.y && z == other.z && (owner != null && owner.equals(other.owner)) &&
                (world != null && world.equals(other.world));
    }

    @Override
    public int hashCode() {
        int hash = 17;

        // the identifier is normally unique, but in SQLite ids may be quickly reused so we use other data
        hash *= 37 + id;

        // coordinates
        hash *= 37 + x;
        hash *= 37 + y;
        hash *= 37 + z;

        // and for good measure, to *guarantee* no collisions
        hash *= 37 + creation.hashCode();

        return hash;
    }

    /**
     * Encode the AccessRights to JSON
     *
     * @return
     */
    public void encodeRights() {
        // create the root
        JSONArray root = new JSONArray();

        // add all of the access rights to the root
        for (AccessRight right : accessRights) {
            if (right != null) {
                root.add(right.encodeToJSON());
            }
        }

        data.put("rights", root);
    }

    /**
     * Encode the protection flags to JSON
     */
    public void encodeFlags() {
        JSONArray root = new JSONArray();

        for (Flag flag : flags) {
            if (flag != null) {
                root.add(flag.getData());
            }
        }

        data.put("flags", root);
    }

    /**
     * Ensure a history object is located in our cache
     *
     * @param history
     */
    public void checkHistory(History history) {
        if (!historyCache.contains(history)) {
            historyCache.add(history);
        }
    }

    /**
     * Check if a player is the owner of the protection
     *
     * @param player
     * @return
     */
    public boolean isOwner(Player player) {
        LWC lwc = LWC.getInstance();

        return player != null && (owner.equals(player.getName()) || lwc.isAdmin(player));
    }

    /**
     * Create a History object that is attached to this protection
     *
     * @return
     */
    public History createHistoryObject() {
        History history = new History();

        history.setProtectionId(id);
        history.setProtection(this);
        history.setStatus(History.Status.INACTIVE);
        history.setX(x);
        history.setY(y);
        history.setZ(z);

        // add it to the cache
        historyCache.add(history);

        return history;
    }

    /**
     * @return the related history for this protection, which is immutable
     */
    public Set<History> getRelatedHistory() {
        // cache the database's history if we don't have any yet
        if (historyCache.size() == 0) {
            historyCache.addAll(LWC.getInstance().getPhysicalDatabase().loadHistory(this));
        }

        // now we can return an immutable cache
        return Collections.unmodifiableSet(historyCache);
    }

    /**
     * Get the related history for this protection using the given type
     *
     * @param type
     * @return
     */
    public List<History> getRelatedHistory(History.Type type) {
        List<History> matches = new ArrayList<History>();
        Set<History> relatedHistory = getRelatedHistory();

        for (History history : relatedHistory) {
            if (history.getType() == type) {
                matches.add(history);
            }
        }

        return matches;
    }

    /**
     * Check if a flag is enabled
     *
     * @param type
     * @return
     */
    public boolean hasFlag(Flag.Type type) {
        for (Flag flag : flags) {
            if (flag.getType() == type) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get the enabled flag for the corresponding type
     *
     * @param type
     * @return
     */
    public Flag getFlag(Flag.Type type) {
        for (Flag flag : flags) {
            if (flag.getType() == type) {
                return flag;
            }
        }

        return null;
    }

    /**
     * Add a flag to the protection
     *
     * @param flag
     * @return
     */
    public boolean addFlag(Flag flag) {
        if (removed || flag == null) {
            return false;
        }

        if (!flags.contains(flag)) {
            flags.add(flag);
            modified = true;
            return true;
        }

        return false;
    }

    /**
     * Remove a flag from the protection
     * TODO: redo? :s
     *
     * @param flag
     * @return
     */
    public void removeFlag(Flag flag) {
        if (removed) {
            return;
        }

        flags.remove(flag);
        this.modified = true;
    }

    /**
     * Check if the entity + accessRights type exists, and if so return the rights (-1 if it does not exist)
     *
     * @param type
     * @param name
     * @return the accessRights the player has
     */
    public int getAccess(int type, String name) {
        for (AccessRight right : accessRights) {
            if (right.getType() == type && right.getName().equalsIgnoreCase(name)) {
                return right.getRights();
            }
        }

        return -1;
    }

    /**
     * @return the list of access rights
     */
    public List<AccessRight> getAccessRights() {
        return Collections.unmodifiableList(new ArrayList<AccessRight>(accessRights));
    }

    /**
     * Remove temporary accessRights rights from the protection
     */
    public void removeTemporaryAccessRights() {
        removeAccessRightsMatching("*", AccessRight.TEMPORARY);
    }

    /**
     * Add an accessRights right to the stored list
     *
     * @param right
     */
    public void addAccessRight(AccessRight right) {
        if (removed || right == null) {
            return;
        }

        // remove any other rights with the same identity
        removeAccessRightsMatching(right.getName(), right.getType());

        // now we can safely add it
        accessRights.add(right);
        modified = true;
    }

    /**
     * Remove access rights from the protection that match an entity AND type
     *
     * @param entity
     * @param type
     */
    public void removeAccessRightsMatching(String entity, int type) {
        if (removed) {
            return;
        }

        Iterator<AccessRight> iter = accessRights.iterator();

        while (iter.hasNext()) {
            AccessRight right = iter.next();

            if ((right.getName().equals(entity) || entity.equals("*")) && right.getType() == type) {
                iter.remove();
                modified = true;
            }
        }
    }

    public JSONObject getData() {
        return data;
    }

    public int getBlockId() {
        return blockId;
    }

    public String getPassword() {
        return password;
    }

    public String getCreation() {
        return creation;
    }

    public int getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public Type getType() {
        return type;
    }

    public String getWorld() {
        return world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public long getLastAccessed() {
        return lastAccessed;
    }

    public void setBlockId(int blockId) {
        if (removed) {
            return;
        }

        this.blockId = blockId;
        this.modified = true;
    }

    public void setPassword(String password) {
        if (removed) {
            return;
        }

        this.password = password;
        this.modified = true;
    }

    public void setCreation(String creation) {
        if (removed) {
            return;
        }

        this.creation = creation;
        this.modified = true;
    }

    public void setId(int id) {
        if (removed) {
            return;
        }

        this.id = id;
        this.modified = true;
    }

    public void setOwner(String owner) {
        if (removed) {
            return;
        }

        this.owner = owner;
        this.modified = true;
    }

    public void setType(Type type) {
        if (removed) {
            return;
        }

        this.type = type;
        this.modified = true;
    }

    public void setWorld(String world) {
        if (removed) {
            return;
        }

        this.world = world;
        this.modified = true;
    }

    public void setX(int x) {
        if (removed) {
            return;
        }

        this.x = x;
        this.modified = true;
    }

    public void setY(int y) {
        if (removed) {
            return;
        }

        this.y = y;
        this.modified = true;
    }

    public void setZ(int z) {
        if (removed) {
            return;
        }

        this.z = z;
        this.modified = true;
    }

    public void setLastAccessed(long lastAccessed) {
        if (removed) {
            return;
        }

        this.lastAccessed = lastAccessed;
        this.modified = true;
    }

    /**
     * Remove the protection from the database
     */
    public void remove() {
        if (removed) {
            return;
        }

        LWC lwc = LWC.getInstance();
        removeTemporaryAccessRights();

        // we're removing it, so assume there are no changes
        modified = false;
        removing = true;

        // broadcast the removal event
        // we broadcast before actually removing to give them a chance to use any password that would be removed otherwise
        lwc.getModuleLoader().dispatchEvent(new LWCProtectionRemovePostEvent(this));

        // mark related transactions as inactive
        for (History history : getRelatedHistory(History.Type.TRANSACTION)) {
            if (history.getStatus() != History.Status.ACTIVE) {
                continue;
            }

            history.setStatus(History.Status.INACTIVE);
        }

        // ensure all history objects for this protection are saved
        checkAndSaveHistory();

        // make the protection immutable
        removed = true;

        // and now finally remove it from the database
        lwc.getUpdateThread().unqueueProtectionUpdate(this);
        lwc.getPhysicalDatabase().removeProtection(id);
        removeCache();
    }

    /**
     * Remove the protection from cache
     */
    public void removeCache() {
        LWC lwc = LWC.getInstance();
        lwc.getProtectionCache().remove(this);
    }

    /**
     * Queue the protection to be saved
     */
    public void save() {
        if (removed) {
            return;
        }

        LWC.getInstance().getUpdateThread().queueProtectionUpdate(this);
    }

    /**
     * Force a protection update to the live database
     */
    public void saveNow() {
        if (removed) {
            return;
        }

        // encode JSON objects
        encodeRights();
        encodeFlags();

        // only save the protection if it was modified
        if (modified && !removing) {
            LWC.getInstance().getPhysicalDatabase().saveProtection(this);
        }

        // check the cache for history updates
        checkAndSaveHistory();
    }

    /**
     * Saves any of the history items for the Protection that have been modified
     */
    public void checkAndSaveHistory() {
        if (removed) {
            return;
        }

        for (History history : getRelatedHistory()) {
            // if the history object was modified we need to save it
            if (history.wasModified()) {
                history.saveNow();
            }
        }
    }

    /**
     * @return the key used for the protection cache
     */
    public String getCacheKey() {
        return world + ":" + x + ":" + y + ":" + z;
    }

    /**
     * @return the Bukkit world the protection should be located in
     */
    public World getBukkitWorld() {
        if (world == null || world.isEmpty()) {
            return Bukkit.getServer().getWorlds().get(0);
        }

        return Bukkit.getServer().getWorld(world);
    }

    /**
     * @return the Bukkit Player object of the owner
     */
    public Player getBukkitOwner() {
        return Bukkit.getServer().getPlayer(owner);
    }

    /**
     * @return the block representing the protection in the world
     */
    public Block getBlock() {
        World world = getBukkitWorld();

        if (world == null) {
            return null;
        }

        return world.getBlockAt(x, y, z);
    }

    /**
     * @return
     */
    @Override
    public String toString() {
        // format the flags prettily
        String flagStr = "";

        for (Flag flag : flags) {
            flagStr += flag.toString() + ",";
        }

        if (flagStr.endsWith(",")) {
            flagStr = flagStr.substring(0, flagStr.length() - 1);
        }

        // format the last accessed time
        String lastAccessed = TimeUtil.timeToString((System.currentTimeMillis() / 1000L) - this.lastAccessed);

        if (!lastAccessed.equals("Not yet known")) {
            lastAccessed += " ago";
        }

        return String.format("%s %s" + Colors.White + " " + Colors.Green + "Id=%d Owner=%s Location=[%s %d,%d,%d] Created=%s Flags=%s LastAccessed=%s", typeToString(), (blockId > 0 ? (LWC.materialToString(blockId)) : "Not yet cached"), id, owner, world, x, y, z, creation, flagStr, lastAccessed);
    }

    /**
     * @return string representation of the protection type
     */
    public String typeToString() {
        return StringUtil.capitalizeFirstLetter(type.toString());
    }

    /**
     * Updates the protection in the protection cache
     */
    @Deprecated
    public void update() {
        throw new UnsupportedOperationException("Protection.update() is no longer necessary!");
    }

}
