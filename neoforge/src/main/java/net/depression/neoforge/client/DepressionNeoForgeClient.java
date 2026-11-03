package net.depression.neoforge.client;

import net.depression.Depression;
import net.depression.client.DepressionClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = Depression.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class DepressionNeoForgeClient
{
    //若运行端位为客户端，则初始化客户端的对象
    @SubscribeEvent
    public static void onInitializeClient(FMLClientSetupEvent event) {
        DepressionClient.onInitializeClient();
    }

}
