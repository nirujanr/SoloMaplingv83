/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import scripting.quest.QuestScriptManager;
import server.quest.Quest;

/**
 * @author Matze
 */
public final class QuestActionHandler extends AbstractPacketHandler {

    // Originally isNpcNearby (thanks GabrielSin), which also required the player to be within
    // 1200 x 800 px of the NPC. SoloMapling drops the distance part: tall maps like Ellinia put
    // quest NPCs 2000+ px from the bottom, so quest-log / quest-helper accepts kept failing.
    // The NPC still has to be on the player's current map.
    private static boolean isNpcOnMap(InPacket p, Character player, Quest quest, int npcId) {
        if (p.available() >= 4) {
            p.readShort();  // client-reported player x/y: unused, but must be consumed
            p.readShort();  // so the reward selection that follows is read correctly
        }

        if (!quest.isAutoStart() && !quest.isAutoComplete()) {
            if (player.getMap().getNPCById(npcId) == null) {
                player.dropMessage(5, "Go to the NPC's map to fulfill this quest operation.");
                return false;
            }
        }

        return true;
    }

    @Override
    public final void handlePacket(InPacket p, Client c) {
        byte action = p.readByte();
        short questid = p.readShort();
        Character player = c.getPlayer();
        Quest quest = Quest.getInstance(questid);

        switch (action) {
        case 0: // Restore lost item, Credits Darter ( Rajan )
            p.readInt();
            int itemid = p.readInt();
            quest.restoreLostItem(player, itemid);
            break;
        case 1: { // Start Quest
            int npc = p.readInt();
            if (!isNpcOnMap(p, player, quest, npc)) {
                return;
            }
            if (quest.canStart(player, npc)) {
                quest.start(player, npc);
            }
            break;
        }
        case 2: { // Complete Quest
            int npc = p.readInt();
            if (!isNpcOnMap(p, player, quest, npc)) {
                return;
            }
            if (quest.canComplete(player, npc)) {
                if (p.available() >= 2) {
                    int selection = p.readShort();
                    quest.complete(player, npc, selection);
                } else {
                    quest.complete(player, npc);
                }
            }
            break;
        }
        case 3: // forfeit quest
            quest.forfeit(player);
            break;
        case 4: { // scripted start quest
            int npc = p.readInt();
            if (!isNpcOnMap(p, player, quest, npc)) {
                return;
            }
            if (quest.canStart(player, npc)) {
                QuestScriptManager.getInstance().start(c, questid, npc);
            }
            break;
        }
        case 5: { // scripted end quests
            int npc = p.readInt();
            if (!isNpcOnMap(p, player, quest, npc)) {
                return;
            }
            if (quest.canComplete(player, npc)) {
                QuestScriptManager.getInstance().end(c, questid, npc);
            }
            break;
        }
        }
    }
}
