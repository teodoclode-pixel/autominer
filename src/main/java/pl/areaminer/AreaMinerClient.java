package pl.areaminer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class AreaMinerClient implements ClientModInitializer {
    private boolean hadScreen = false;

    @Override
    public void onInitializeClient() {
        // Gdy otworzy sie jakiekolwiek okno (czat, ESC/menu, ekwipunek...) -> wyslij stop do serwera.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean hasScreen = client.currentScreen != null;
            if (hasScreen && !hadScreen && client.player != null
                    && ClientPlayNetworking.canSend(StopPayload.ID)) {
                ClientPlayNetworking.send(new StopPayload());
            }
            hadScreen = hasScreen;
        });
    }
}
