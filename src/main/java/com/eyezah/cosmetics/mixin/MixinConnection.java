package com.eyezah.cosmetics.mixin;

import com.eyezah.cosmetics.Authentication;
import com.eyezah.cosmetics.screens.UnauthenticatedScreen;
import com.eyezah.cosmetics.utils.LoadingTypeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class MixinConnection {
	@Inject(at = @At("HEAD"), method = "disconnect")
	public void onDisconnect(Component component, CallbackInfo ci) {
		if (component.getString().startsWith("ExtravagantCosmeticsToken:")) {
			Authentication.requestTokens(component.getString().substring(26));
		} else {
			Authentication.currentlyAuthenticating = false;

			if (Minecraft.getInstance().screen instanceof LoadingTypeScreen lts) {
				Minecraft.getInstance().tell(() -> Minecraft.getInstance().setScreen(new UnauthenticatedScreen(lts.getParent(), false)));
			}
		}
	}
}
