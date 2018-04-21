/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.lib;

import buildcraft.lib.item.ItemDebugger;
import buildcraft.lib.item.ItemGuide;
import buildcraft.lib.item.ItemGuideNote;
import buildcraft.lib.registry.RegistrationHelper;

public class BCLibItems {

    public static ItemGuide guide;
    public static ItemGuideNote note;
    public static ItemDebugger debugger;

    private static boolean enableGuide, enableDebugger;

    public static void enableGuide() {
        enableGuide = true;
    }

    public static void enableDebugger() {
        enableDebugger = true;
    }

    public static void fmlPreInit() {
        if (enableGuide) {
            guide = RegistrationHelper.addForcedItem(new ItemGuide("item.guide"));
            note = RegistrationHelper.addForcedItem(new ItemGuideNote("item.guide.note"));
        }
        if (enableDebugger) {
            debugger = RegistrationHelper.addForcedItem(new ItemDebugger("item.debugger"));
        }
    }
}
