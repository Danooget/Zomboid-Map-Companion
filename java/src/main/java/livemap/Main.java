package livemap;

import me.zed_0xff.zombie_buddy.Exposer;

public class Main {
    public static void main(String[] args) {
        System.out.println("[LiveWebMap] ZombieBuddy Java mod loaded!");
        // Preserve the existing F7 panel and Lua bridge.
        Exposer.addClassWithGlobalLuaMethod(LuaBridge.class);
        // Register two global functions for the isolated MCEF probe.
        Exposer.addClassWithGlobalLuaMethod(McefProbe.class);
        System.out.println("[LiveWebMap] Lua bridge and MCEF probe registered!");
    }
}
