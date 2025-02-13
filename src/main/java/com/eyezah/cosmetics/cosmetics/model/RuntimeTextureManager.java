package com.eyezah.cosmetics.cosmetics.model;

import com.eyezah.cosmetics.Cosmetica;
import com.eyezah.cosmetics.mixin.textures.MixinTextureAtlasSpriteInvoker;
import com.eyezah.cosmetics.utils.Debug;
import com.eyezah.cosmetics.utils.Scheduler;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.w3c.dom.Text;

import java.util.function.Consumer;

/**
 * Queue-like texture cache. First-Come, First-Served.
 */
public class RuntimeTextureManager {
	/**
	 * @param size the size of the cache, must be a power of tool due to bitwise operations utilised (technically could use modulo instead but speed)
	 */
	public RuntimeTextureManager(int size) {
		this.size = size;
		this.ids = new String[size];
		this.sprites = new TextureAtlasSprite[size];
		this.used = new int[size];
	}

	private final int size;
	private final String[] ids; // the numbers index by a resource location
	private final TextureAtlasSprite[] sprites; // the sprite at an index
	private int emptySpriteIndex = 0;

	// to limit the number of textures loaded and thus baked models created per tick, should this ever be necessary on a server
	long lastTickTime; // default long value = 0 should be fine
	final int[] used; // 0 = unused, 1 = used last tick, 2 = used this tick, 3 - MAX_VALUE = searching
	int search = 0; // current search index for an unused space (reset each tick)

	public void addAtlasSprite(TextureAtlasSprite result) {
		this.sprites[this.emptySpriteIndex] = result;
		this.emptySpriteIndex = (this.emptySpriteIndex + 1) & (this.size - 1);
	}

	void clear() {
		this.search = 0;

		for (int i = 0; i < this.size; ++i) {
			this.used[i] = 0;
			this.ids[i] = null;
		}
	}

	// should be called from the render thread because of texture setting probably
	// andThen may not be called on the same thread -- proceed with caution
	public void retrieveAllocatedSprite(BakableModel model, long tickTime, Consumer<TextureAtlasSprite> callback) {
		if (tickTime != this.lastTickTime) {
			this.lastTickTime = tickTime;
			this.search = 0;

			for (int i = 0; i < this.size; ++i) {
				if (this.used[i] > 0) this.used[i]--; // once it reaches zero it's not gonna be overwritten just yet, but it will be marked as able to be overwritten. So if it needs the space it will overwrite it.
			}
		}

		int index = this.getIndex(model.id());

		if (index == -1) {
			if (this.search == this.size) return;
			index = this.search;

			while (this.used[index] > 0) {
				// increment. if reached the end, cannot load any new textures
				if (++index == this.size) { // attention code editors: keep the ++ operator before index! ++index returns the result after incrementing, whereas index++ returns the result before!
					this.search = this.size;
					return; // return silently. we ran out of space. no major worry.
				}
			}

			Debug.info("Using New Index: " + index);
			//System.out.println("Count: " + ((MixinTextureAtlasSpriteInvoker)this.sprites[index]).getMainImage().length); Count: 5
			// at this point, index is guaranteed to be a value which is free
			this.search = index + 1; // the next spot over

			// use this index of reserved texture
			// remove existing associated model
			if (this.ids[index] != null) Models.removeBakedModel(this.ids[index]);
			// upload new model
			this.ids[index] = model.id();
			this.used[index] = Integer.MAX_VALUE; // basically indefinitely marking it as unuseable

			TextureAtlasSprite sprite = this.sprites[index];

			if (sprite == null) { // this should not happen, however it does seem to be happening sometimes if Iris is installed, so here's a catch to not destroy the game and give the game some time.
				Cosmetica.LOGGER.error("The sprite assigned to model {} is null! Will try again in 20 ticks.", model.id());
				this.used[index] = 20;
				return; // don't run code that requires it
			}

			final int index_ = index;

			Scheduler.scheduleTask(Scheduler.Location.TEXTURE_TICK, () -> {
				// generate mipmap
				NativeImage[] mipmap = MipmapGenerator.generateMipLevels(model.image(), ((MixinTextureAtlasSpriteInvoker) sprite).getMainImage().length - 1);
				Debug.info("Allocating Sprite: " + sprite.getName());
				Debug.dumpImages(sprite.getName().toDebugFileName() + "_old", false, ((MixinTextureAtlasSpriteInvoker) sprite).getMainImage());
				Debug.dumpImages(sprite.getName().toDebugFileName(), false, mipmap);
				// bind the texture
				GlStateManager._bindTexture(((MixinTextureAtlasSpriteInvoker) sprite).getAtlas().getId());
				// upload to the texture
				((MixinTextureAtlasSpriteInvoker) sprite).callUpload(0, 0, mipmap);
				//sprite.uploadFirstFrame();
				this.used[index_] = 2;
				callback.accept(sprite);
			});
		}

		TextureAtlasSprite sprite = this.sprites[index];

		if (sprite != null) {
			// mark it as still being used
			this.used[index] = 2;
			callback.accept(sprite);
		} else if (this.used[0] == 0) { // after 20 ticks make it try again by dissociating the model
			Cosmetica.LOGGER.info("Preparing to try assign a sprite for {} again...", model.id());
		}
	}

	// literally just search the entire array to see if it exists
	// don't sort the array so probably the fastest way aside from creating and calling a native method with JNI which is overkill
	private int getIndex(String id) {
		for (int i = 0; i < this.size; ++i) {
			if (id.equals(this.ids[i])) {
				return i;
			}
		}

		return -1;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("RuntimeTextureManager");

		for (int i = 0; i < this.size; ++i) {
			if (this.used[i] > 0) {
				sb.append("[u:").append(this.used[i]).append(",k:").append(this.ids[i]).append("]");
			}
		}

		return sb.toString();
	}
}
