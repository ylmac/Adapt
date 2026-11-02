package com.ylmac.adapt.module.render;

import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;

/** 第一人称挥击改回 1.7 的下劈，不再用现在的前戳。 */
public class OldSwing extends Module {

    public static OldSwing INSTANCE;

    public OldSwing() {
        super("oldswing", "1.7挥击", Category.RENDER);
        INSTANCE = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    public static boolean active() {
        return INSTANCE != null && INSTANCE.isEnabled();
    }
}
