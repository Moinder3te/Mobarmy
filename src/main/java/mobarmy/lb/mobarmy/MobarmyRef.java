package mobarmy.lb.mobarmy;

import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MobarmyRef {
    public static final String MOD_ID = "mobarmy";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private MobarmyRef() {}

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}

