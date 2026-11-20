/*
    SoloMapling: Captain Al's Passing of Knowledge - "Enjoy the Entitlement!"
    NPC: 1002103 (Captain Al, Lith Harbor)
    Complete: total Family Rep 2,000+ and current Rep below 500 (i.e. Rep spent on Entitlements).
*/

var REQUIRED_TOTAL_REP = 2000;
var MAX_CURRENT_REP = 500;

function start(mode, type, selection) {
    qm.forceStartQuest();
    qm.dispose();
}

function end(mode, type, selection) {
    var familyEntry = qm.getPlayer().getFamilyEntry();
    var total = familyEntry != null ? familyEntry.getTotalReputation() : 0;
    var current = familyEntry != null ? familyEntry.getReputation() : 0;
    if (total >= REQUIRED_TOTAL_REP && current < MAX_CURRENT_REP) {
        qm.forceCompleteQuest();
        qm.gainExp(8000);
        qm.sendNext("Now you understand the true joy of a Family - the Entitlement! You've learned everything I can teach you. Lead your Family well.");
    } else if (total < REQUIRED_TOTAL_REP) {
        qm.sendNext("Your total Rep is #b" + total + "#k. Reach #r" + REQUIRED_TOTAL_REP + "#k total Rep first, then spend it on Entitlements.");
    } else {
        qm.sendNext("Your current Rep is still #b" + current + "#k. Use your Entitlements from the Family window until it's below #r" + MAX_CURRENT_REP + "#k, then report back.");
    }
    qm.dispose();
}
