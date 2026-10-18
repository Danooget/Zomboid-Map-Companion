
package livemap;

import se.krka.kahlua.integration.annotations.LuaMethod;

public class LuaBridge {
    @LuaMethod(name = "liveWebMapTest", global = true)
    public static void test() {
        System.out.println("[LiveWebMap] Lua successfully called Java!");
    }

    @LuaMethod(name = "liveWebMapTogglePanel", global = true)
    public static void togglePanel() {
        LiveWebMapPanel.toggle();
        System.out.println("[LiveWebMap] In-game panel toggle requested!");
    }
}
