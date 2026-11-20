/*
    SoloMapling: Captain Al's Passing of Knowledge - "Raise the Rep!"
    NPC: 1002103 (Captain Al, Lith Harbor)
    Complete: reach 1,000 total Family Rep. Follows 2232.js.
*/

var REQUIRED_TOTAL_REP = 1000;

function start(mode, type, selection) {
    qm.forceStartQuest();
    qm.dispose();
}

function end(mode, type, selection) {
    var familyEntry = qm.getPlayer().getFamilyEntry();
    var total = familyEntry != null ? familyEntry.getTotalReputation() : 0;
    if (total >= REQUIRED_TOTAL_REP) {
        qm.forceCompleteQuest();
        qm.gainExp(5000);
        qm.sendNext("#b" + total + " Rep#k! Your Juniors must be thriving under your care. You've earned my respect, and this reward.");
    } else {
        qm.sendNext("You have #b" + total + "#k total Rep so far. Come back when you've reached #r" + REQUIRED_TOTAL_REP + " Rep#k. Help your Juniors grow and the Rep will follow.");
    }
    qm.dispose();
}
