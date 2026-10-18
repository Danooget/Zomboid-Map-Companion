
package livemap;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.ui.UIElement;
import zombie.ui.UIManager;
import zombie.ui.UIFont;

@Patch(className = "zombie.gameStates.MainScreenState",
       methodName = "renderBackground")
public class Patch_MainScreenState {
    public static UIElement panel;

    public static void togglePanel() {
        if (panel == null) {
            panel = new UIElement();
            panel.setX(100);
            panel.setY(100);
            panel.setWidth(800);
            panel.setHeight(600);
            panel.setVisible(true);
            panel.setAlwaysOnTop(true);
            UIManager.AddUI(panel);
        } else {
            panel.setVisible(!panel.isVisible());
        }
    }

    @Patch.OnExit
    public static void exit() {
        if (panel != null && panel.isVisible()) {
            panel.DrawText(
                UIFont.Medium,
                "Live Web Map - F7 panel test",
                20, 20,
                1.0, 1.0, 1.0, 1.0
            );
        }
    }
}
