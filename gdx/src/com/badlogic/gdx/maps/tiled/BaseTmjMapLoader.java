
package com.badlogic.gdx.maps.tiled;

import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.loaders.AsynchronousAssetLoader;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.TextureLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.maps.*;
import com.badlogic.gdx.maps.objects.EllipseMapObject;
import com.badlogic.gdx.maps.objects.PolygonMapObject;
import com.badlogic.gdx.maps.objects.PolylineMapObject;
import com.badlogic.gdx.maps.objects.RectangleMapObject;
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer.Cell;
import com.badlogic.gdx.maps.tiled.objects.TiledMapTileMapObject;
import com.badlogic.gdx.maps.tiled.tiles.AnimatedTiledMapTile;
import com.badlogic.gdx.maps.tiled.tiles.StaticTiledMapTile;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Polyline;
import com.badlogic.gdx.utils.*;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public abstract class BaseTmjMapLoader<P extends BaseTmjMapLoader.Parameters> extends AsynchronousAssetLoader<TiledMap, P> {

	protected static final int FLAG_FLIP_HORIZONTALLY = 0x80000000;
	protected static final int FLAG_FLIP_VERTICALLY = 0x40000000;
	protected static final int FLAG_FLIP_DIAGONALLY = 0x20000000;
	protected static final int MASK_CLEAR = 0xE0000000;
	protected JsonReader json = new JsonReader();
	protected JsonValue root;
	protected boolean convertObjectToTileSpace;
	protected boolean flipY = true;
	protected int mapTileWidth;
	protected int mapTileHeight;
	protected int mapWidthInPixels;
	protected int mapHeightInPixels;
	protected TiledMap map;
	protected IntMap<MapObject> idToObject;
	protected Array<Runnable> runOnEndOfLoadTiled;

	public BaseTmjMapLoader (FileHandleResolver resolver) {
		super(resolver);
	}

	static public int[] getTileIds (JsonValue element, int width, int height) {
		JsonValue data = element.get("data");
		String encoding = element.getString("encoding", null);

		int[] ids;
		if (encoding == null || encoding.isEmpty() || encoding.equals("csv")) {
			ids = data.asIntArray();
		} else if (encoding.equals("base64")) {
			InputStream is = null;
			try {
				String compression = element.getString("compression", null);
				byte[] bytes = Base64Coder.decode(data.asString());
				if (compression == null || compression.isEmpty())
					is = new ByteArrayInputStream(bytes);
				else if (compression.equals("gzip"))
					is = new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes), bytes.length));
				else if (compression.equals("zlib"))
					is = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)));
				else
					throw new GdxRuntimeException("Unrecognised compression (" + compression + ") for TMJ Layer Data");

				byte[] temp = new byte[4];
				ids = new int[width * height];
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int read = is.read(temp);
						while (read < temp.length) {
							int curr = is.read(temp, read, temp.length - read);
							if (curr == -1) break;
							read += curr;
						}
						if (read != temp.length)
							throw new GdxRuntimeException("Error Reading TMJ Layer Data: Premature end of tile data");
						ids[y * width + x] = unsignedByteToInt(temp[0]) | unsignedByteToInt(temp[1]) << 8
							| unsignedByteToInt(temp[2]) << 16 | unsignedByteToInt(temp[3]) << 24;
					}
				}
			} catch (IOException e) {
				throw new GdxRuntimeException("Error Reading TMJ Layer Data - IOException: " + e.getMessage());
			} finally {
				StreamUtils.closeQuietly(is);
			}
		} else {
			// any other value of 'encoding' is one we're not aware of, probably a feature of a future version of Tiled
			// or another editor
			throw new GdxRuntimeException("Unrecognised encoding (" + encoding + ") for TMJ Layer Data");
		}
		System.out.println(Arrays.toString(ids));
		return ids;
	}

	private static int unsignedByteToInt (byte b) {
		return b & 0xFF;
	}

	private static float[] convertArrayToPrimitive (List<Float> list) {
		float[] ret = new float[list.size()];
		Iterator<Float> iterator = list.iterator();
		for (int i = 0; i < ret.length; i++) {
			ret[i] = iterator.next().intValue();
		}
		return ret;
	}

	protected static FileHandle getRelativeFileHandle (FileHandle file, String path) {
		StringTokenizer tokenizer = new StringTokenizer(path, "\\/");
		FileHandle result = file.parent();
		while (tokenizer.hasMoreElements()) {
			String token = tokenizer.nextToken();
			if (token.equals(".."))
				result = result.parent();
			else {
				result = result.child(token);
			}
		}
		return result;
	}

	@Override
	public Array<AssetDescriptor> getDependencies (String fileName, FileHandle tmjFile, P parameter) {
		this.root = json.parse(tmjFile);

		TextureLoader.TextureParameter textureParameter = new TextureLoader.TextureParameter();
		if (parameter != null) {
			textureParameter.genMipMaps = parameter.generateMipMaps;
			textureParameter.minFilter = parameter.textureMinFilter;
			textureParameter.magFilter = parameter.textureMagFilter;
		}

		return getDependencyAssetDescriptors(tmjFile, textureParameter);
	}

	/** Gets a map of the object ids to the {@link MapObject} instances. Returns null if
	 * {@link #loadTiledMap(FileHandle, BaseTmjMapLoader.Parameters, ImageResolver)} has not been called yet.
	 *
	 * @return the map of the ids to {@link MapObject}, or null if
	 *         {@link #loadTiledMap(FileHandle, BaseTmjMapLoader.Parameters, ImageResolver)} method has not been called yet. */
	public @Null IntMap<MapObject> getIdToObject () {
		return idToObject;
	}

	protected abstract Array<AssetDescriptor> getDependencyAssetDescriptors (FileHandle tmjFile,
		TextureLoader.TextureParameter textureParameter);

	/** Loads the map data, given the JSON root element
	 *
	 * @param tmjFile the Filehandle of the tmj file
	 * @param parameter
	 * @param imageResolver
	 * @return the {@link TiledMap} */
	protected TiledMap loadTiledMap (FileHandle tmjFile, P parameter, ImageResolver imageResolver) {
		this.map = new TiledMap();
		this.idToObject = new IntMap<>();
		this.runOnEndOfLoadTiled = new Array<>();

		if (parameter != null) {
			this.convertObjectToTileSpace = parameter.convertObjectToTileSpace;
			this.flipY = parameter.flipY;
		} else {
			this.convertObjectToTileSpace = false;
			this.flipY = true;
		}
		String mapOrientation = root.getString("orientation", null);
		int mapWidth = root.getInt("width", 0);
		int mapHeight = root.getInt("height", 0);
		int tileWidth = root.getInt("tilewidth", 0);
		int tileHeight = root.getInt("tileheight", 0);
		int hexSideLength = root.getInt("hexsidelength", 0);
		String staggerAxis = root.getString("staggeraxis", null);
		String staggerIndex = root.getString("staggerindex", null);
		String mapBackgroundColor = root.getString("backgroundcolor", null);

		MapProperties mapProperties = map.getProperties();
		if (mapOrientation != null) {
			mapProperties.put("orientation", mapOrientation);
		}
		mapProperties.put("width", mapWidth);
		mapProperties.put("height", mapHeight);
		mapProperties.put("tilewidth", tileWidth);
		mapProperties.put("tileheight", tileHeight);
		mapProperties.put("hexsidelength", hexSideLength);
		if (staggerAxis != null) {
			mapProperties.put("staggeraxis", staggerAxis);
		}
		if (staggerIndex != null) {
			mapProperties.put("staggerindex", staggerIndex);
		}
		if (mapBackgroundColor != null) {
			mapProperties.put("backgroundcolor", mapBackgroundColor);
		}
		this.mapTileWidth = tileWidth;
		this.mapTileHeight = tileHeight;
		this.mapWidthInPixels = mapWidth * tileWidth;
		this.mapHeightInPixels = mapHeight * tileHeight;

		if (mapOrientation != null) {
			if ("staggered".equals(mapOrientation)) {
				if (mapHeight > 1) {
					this.mapWidthInPixels += tileWidth / 2;
					this.mapHeightInPixels = mapHeightInPixels / 2 + tileHeight / 2;
				}
			}
		}

		JsonValue properties = root.get("properties");
		if (properties != null) {
			loadProperties(map.getProperties(), properties);
		}

		JsonValue tileSets = root.get("tilesets");
		for (JsonValue element : tileSets) {
			loadTileSet(element, tmjFile, imageResolver);

		}
		JsonValue layers = root.get("layers");

		for (JsonValue element : layers) {
			loadLayer(map, map.getLayers(), element, tmjFile, imageResolver);
		}

		// update hierarchical parallax scrolling factors
		// in Tiled the final parallax scrolling factor of a layer is the multiplication of its factor with all its parents
		// 1) get top level groups
		final Array<MapGroupLayer> groups = map.getLayers().getByType(MapGroupLayer.class);
		while (groups.notEmpty()) {
			final MapGroupLayer group = groups.first();
			groups.removeIndex(0);

			for (MapLayer child : group.getLayers()) {
				child.setParallaxX(child.getParallaxX() * group.getParallaxX());
				child.setParallaxY(child.getParallaxY() * group.getParallaxY());
				if (child instanceof MapGroupLayer) {
					// 2) handle any child groups
					groups.add((MapGroupLayer)child);
				}
			}
		}

		for (Runnable runnable : runOnEndOfLoadTiled) {
			runnable.run();
		}
		runOnEndOfLoadTiled = null;

		return map;

	}

	protected void loadLayer (TiledMap map, MapLayers parentLayers, JsonValue element, FileHandle tmjFile,
		ImageResolver imageResolver) {
		String type = element.getString("type", "");
		switch (type) {
		case "group":
			loadLayerGroup(map, parentLayers, element, tmjFile, imageResolver);
			break;
		case "tilelayer":
			loadTileLayer(map, parentLayers, element);
			break;
		case "objectgroup":
			loadObjectGroup(map, parentLayers, element);
			break;
		case "imagelayer":
			loadImageLayer(map, parentLayers, element, tmjFile, imageResolver);
			break;
		}
	}

	protected void loadLayerGroup (TiledMap map, MapLayers parentLayers, JsonValue element, FileHandle tmjFile,
		ImageResolver imageResolver) {
		if (element.getString("type", "").equals("group")) {
			MapGroupLayer groupLayer = new MapGroupLayer();
			loadBasicLayerInfo(groupLayer, element);

			JsonValue properties = element.get("properties");
			if (properties != null) {
				loadProperties(groupLayer.getProperties(), properties);
			}
			for (JsonValue child : element) {
				loadLayer(map, groupLayer.getLayers(), child, tmjFile, imageResolver);
			}

			for (MapLayer layer : groupLayer.getLayers()) {
				layer.setParent(groupLayer);
			}

			parentLayers.add(groupLayer);
		}
	}

	protected void loadTileLayer (TiledMap map, MapLayers parentLayers, JsonValue element) {

		if (element.getString("type", "").equals("tilelayer")) {
			int width = element.getInt("width", 0);
			int height = element.getInt("height", 0);
			int tileWidth = map.getProperties().get("tilewidth", Integer.class);
			int tileHeight = map.getProperties().get("tileheight", Integer.class);
			TiledMapTileLayer layer = new TiledMapTileLayer(width, height, tileWidth, tileHeight);

			loadBasicLayerInfo(layer, element);

			int[] ids = getTileIds(element, width, height);
			TiledMapTileSets tileSets = map.getTileSets();
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int id = ids[y * width + x];
					boolean flipHorizontally = ((id & FLAG_FLIP_HORIZONTALLY) != 0);
					boolean flipVertically = ((id & FLAG_FLIP_VERTICALLY) != 0);
					boolean flipDiagonally = ((id & FLAG_FLIP_DIAGONALLY) != 0);

					TiledMapTile tile = tileSets.getTile(id & ~MASK_CLEAR);
					if (tile != null) {
						Cell cell = createTileLayerCell(flipHorizontally, flipVertically, flipDiagonally);
						cell.setTile(tile);
						layer.setCell(x, flipY ? height - 1 - y : y, cell);
					}
				}
			}
			JsonValue properties = element.get("properties");
			if (properties != null) {
				loadProperties(layer.getProperties(), properties);
			}
			parentLayers.add(layer);
		}
	}

	protected void loadObjectGroup (TiledMap map, MapLayers parentLayers, JsonValue element) {
		if (element.getString("type", "").equals("objectgroup")) {
			MapLayer layer = new MapLayer();
			loadBasicLayerInfo(layer, element);
			JsonValue properties = element.get("properties");
			if (properties != null) {
				loadProperties(layer.getProperties(), properties);
			}

			for (JsonValue objectElement : element.get("objects")) {
				loadObject(map, layer, objectElement);
			}

			parentLayers.add(layer);
		}
	}

	protected void loadImageLayer (TiledMap map, MapLayers parentLayers, JsonValue element, FileHandle tmjFile,
		ImageResolver imageResolver) {
		if (element.getString("type", "").equals("imagelayer")) {
			float x = element.getFloat("offsetx", 0);
			float y = element.getFloat("offsety", 0);
			if (flipY) y = mapHeightInPixels - y;

			TextureRegion texture = null;

			JsonValue image = element.get("image");

			if (image != null) {
				String source = image.getString("source");
				FileHandle handle = getRelativeFileHandle(tmjFile, source);
				texture = imageResolver.getImage(handle.path());
				y -= texture.getRegionHeight();
			}

			TiledMapImageLayer layer = new TiledMapImageLayer(texture, x, y);

			loadBasicLayerInfo(layer, element);

			JsonValue properties = element.get("properties");
			if (properties != null) {
				loadProperties(layer.getProperties(), properties);
			}

			parentLayers.add(layer);
		}
	}

	protected void loadBasicLayerInfo (MapLayer layer, JsonValue element) {
		String name = element.getString("name");
		float opacity = element.getFloat("opacity", 1.0f);
		boolean visible = element.getBoolean("visible", true);
		float offsetX = element.getFloat("offsetx", 0);
		float offsetY = element.getFloat("offsety", 0);
		float parallaxX = element.getFloat("parallaxx", 1f);
		float parallaxY = element.getFloat("parallaxy", 1f);

		layer.setName(name);
		layer.setOpacity(opacity);
		layer.setVisible(visible);
		layer.setOffsetX(offsetX);
		layer.setOffsetY(offsetY);
		layer.setParallaxX(parallaxX);
		layer.setParallaxY(parallaxY);
	}

	protected void loadObject (TiledMap map, MapLayer layer, JsonValue element) {
		loadObject(map, layer.getObjects(), element, mapHeightInPixels);
	}

	protected void loadObject (TiledMap map, TiledMapTile tile, JsonValue element) {
		loadObject(map, tile.getObjects(), element, tile.getTextureRegion().getRegionHeight());
	}

	protected void loadObject (TiledMap map, MapObjects objects, JsonValue element, float heightInPixels) {
		if (element.getString("type", "").equals("object")) {
			MapObject object = null;

			float scaleX = convertObjectToTileSpace ? 1.0f / mapTileWidth : 1.0f;
			float scaleY = convertObjectToTileSpace ? 1.0f / mapTileHeight : 1.0f;

			float x = element.getFloat("x", 0) * scaleX;
			float y = (flipY ? (heightInPixels - element.getFloat("y", 0)) : element.getFloat("y", 0)) * scaleY;

			float width = element.getFloat("width", 0) * scaleX;
			float height = element.getFloat("height", 0) * scaleY;

			// if (element.getChildCount() > 0) {
			JsonValue child;
			if ((child = element.get("polygon")) != null) {
				ArrayList<Float> vertices = new ArrayList<>();
				for (JsonValue point : child) {
					vertices.add(point.getFloat("x", 0) * scaleX);
					vertices.add(point.getFloat("y", 0) * scaleY * (flipY ? -1 : 1));
				}
				Polygon polygon = new Polygon(convertArrayToPrimitive(vertices));
				polygon.setPosition(x, y);
				object = new PolygonMapObject(polygon);
			} else if ((child = element.get("polyline")) != null) {
				ArrayList<Float> vertices = new ArrayList<>();
				for (JsonValue point : child) {
					vertices.add(point.getFloat("x", 0) * scaleX);
					vertices.add(point.getFloat("y", 0) * scaleY * (flipY ? -1 : 1));
				}
				Polyline polyline = new Polyline(convertArrayToPrimitive(vertices));
				polyline.setPosition(x, y);
				object = new PolylineMapObject(polyline);
			} else if (element.get("ellipse") != null) {
				object = new EllipseMapObject(x, flipY ? y - height : y, width, height);
			}

			if (object == null) {
				String gid;
				if ((gid = element.getString("gid", null)) != null) {
					int id = (int)Long.parseLong(gid);
					boolean flipHorizontally = ((id & FLAG_FLIP_HORIZONTALLY) != 0);
					boolean flipVertically = ((id & FLAG_FLIP_VERTICALLY) != 0);

					TiledMapTile tile = map.getTileSets().getTile(id & ~MASK_CLEAR);
					TiledMapTileMapObject tiledMapTileMapObject = new TiledMapTileMapObject(tile, flipHorizontally, flipVertically);
					TextureRegion textureRegion = tiledMapTileMapObject.getTextureRegion();
					tiledMapTileMapObject.getProperties().put("gid", id);
					tiledMapTileMapObject.setX(x);
					tiledMapTileMapObject.setY(flipY ? y : y - height);
					float objectWidth = element.getFloat("width", textureRegion.getRegionWidth());
					float objectHeight = element.getFloat("height", textureRegion.getRegionHeight());
					tiledMapTileMapObject.setScaleX(scaleX * (objectWidth / textureRegion.getRegionWidth()));
					tiledMapTileMapObject.setScaleY(scaleY * (objectHeight / textureRegion.getRegionHeight()));
					tiledMapTileMapObject.setRotation(element.getFloat("rotation", 0));
					object = tiledMapTileMapObject;
				} else {
					object = new RectangleMapObject(x, flipY ? y - height : y, width, height);
				}
			}
			object.setName(element.getString("name", null));
			String rotation = element.getString("rotation", null);
			if (rotation != null) {
				object.getProperties().put("rotation", Float.parseFloat(rotation));
			}
			String type = element.getString("type", null);
			if (type != null) {
				object.getProperties().put("type", type);
			}
			int id = element.getInt("id", 0);
			if (id != 0) {
				object.getProperties().put("id", id);
			}
			object.getProperties().put("x", x);

			if (object instanceof TiledMapTileMapObject) {
				object.getProperties().put("y", y);
			} else {
				object.getProperties().put("y", (flipY ? y - height : y));
			}
			object.getProperties().put("width", width);
			object.getProperties().put("height", height);
			object.setVisible(element.getBoolean("visible", true));
			JsonValue properties = element.get("properties");
			if (properties != null) {
				loadProperties(object.getProperties(), properties);
			}
			idToObject.put(id, object);
			objects.add(object);
		}
	}

	private void loadProperties (final MapProperties properties, JsonValue element) {
		if (element == null) return;

		if (element.name() != null && element.name().equals("properties")) {
			for (JsonValue property : element) {
				final String name = property.getString("name", null);
				String value = property.getString("value", null);
				String type = property.getString("type", null);
				if (value == null) {
					value = property.asString();
				}
				if (type != null && type.equals("object")) {
					// Wait until the end of [loadTiledMap] to fetch the object
					try {
						// Value should be the id of the object being pointed to
						final int id = Integer.parseInt(value);
						// Create [Runnable] to fetch object and add it to props
						Runnable fetch = new Runnable() {
							@Override
							public void run () {
								MapObject object = idToObject.get(id);
								properties.put(name, object);
							}
						};
						// [Runnable] should not run until the end of [loadTiledMap]
						runOnEndOfLoadTiled.add(fetch);
					} catch (Exception exception) {
						throw new GdxRuntimeException(
							"Error parsing property [\" + name + \"] of type \"object\" with value: [" + value + "]", exception);
					}
				} else {
					Object castValue = castProperty(name, value, type);
					properties.put(name, castValue);
				}
			}
		}
	}

	private Object castProperty (String name, String value, String type) {
		if (type == null) {
			return value;
		} else if (type.equals("int")) {
			return Integer.valueOf(value);
		} else if (type.equals("float")) {
			return Float.valueOf(value);
		} else if (type.equals("bool")) {
			return Boolean.valueOf(value);
		} else if (type.equals("color")) {
			// Tiled uses the format #AARRGGBB
			String opaqueColor = value.substring(3);
			String alpha = value.substring(1, 3);
			return Color.valueOf(opaqueColor + alpha);
		} else {
			throw new GdxRuntimeException(
				"Wrong type given for property " + name + ", given : " + type + ", supported : string, bool, int, float, color");
		}
	}

	private Cell createTileLayerCell (boolean flipHorizontally, boolean flipVertically, boolean flipDiagonally) {
		Cell cell = new Cell();
		if (flipDiagonally) {
			if (flipHorizontally && flipVertically) {
				cell.setFlipHorizontally(true);
				cell.setRotation(Cell.ROTATE_270);
			} else if (flipHorizontally) {
				cell.setRotation(Cell.ROTATE_270);
			} else if (flipVertically) {
				cell.setRotation(Cell.ROTATE_90);
			} else {
				cell.setFlipVertically(true);
				cell.setRotation(Cell.ROTATE_270);
			}
		} else {
			cell.setFlipHorizontally(flipHorizontally);
			cell.setFlipVertically(flipVertically);
		}
		return cell;
	}

	private void loadTileSet (JsonValue element, FileHandle tmjFile, ImageResolver imageResolver) {
		if (element.getString("tilecount") != null) { // Specific tileSet attribute
			int firstgid = element.getInt("firstgid", 1);
			String imageSource = "";
			int imageWidth = 0;
			int imageHeight = 0;
			FileHandle image = null;

			String source = element.getString("source", null);
			if (source != null) {
				FileHandle tsx = getRelativeFileHandle(tmjFile, source);
				try {
					JsonValue imageElement = json.parse(tsx);
					if (element.has("image")) {
						imageSource = imageElement.getString("image");
						imageWidth = imageElement.getInt("imagewidth", 0);
						imageHeight = imageElement.getInt("imageheight", 0);
						image = getRelativeFileHandle(tsx, imageSource);
					}
				} catch (SerializationException e) {
					throw new GdxRuntimeException("Error parsing external tileSet.");
				}
			} else {
				if (element.has("image")) {
					imageSource = element.getString("image");
					imageWidth = element.getInt("imagewidth", 0);
					imageHeight = element.getInt("imageheight", 0);
					image = getRelativeFileHandle(tmjFile, imageSource);
				}
			}
			String name = element.getString("name", null);
			int tilewidth = element.getInt("tilewidth", 0);
			int tileheight = element.getInt("tileheight", 0);
			int spacing = element.getInt("spacing", 0);
			int margin = element.getInt("margin", 0);

			JsonValue offset = element.get("tileoffset");
			int offsetX = 0;
			int offsetY = 0;
			if (offset != null) {
				offsetX = offset.getInt("x", 0);
				offsetY = offset.getInt("y", 0);
			}
			TiledMapTileSet tileSet = new TiledMapTileSet();

			// TileSet
			tileSet.setName(name);
			final MapProperties tileSetProperties = tileSet.getProperties();
			JsonValue properties = element.get("properties");
			if (properties != null) {
				loadProperties(tileSetProperties, properties);
			}
			tileSetProperties.put("firstgid", firstgid);

			// Tiles
			JsonValue tiles = element.get("tiles");

			if (tiles == null) {
				tiles = new JsonValue(JsonValue.ValueType.array);
			}

			addStaticTiles(tmjFile, imageResolver, tileSet, element, tiles, name, firstgid, tilewidth, tileheight, spacing, margin,
				source, offsetX, offsetY, imageSource, imageWidth, imageHeight, image);

			Array<AnimatedTiledMapTile> animatedTiles = new Array<>();

			for (JsonValue tileElement : tiles) {
				int localtid = tileElement.getInt("id", 0);
				TiledMapTile tile = tileSet.getTile(firstgid + localtid);
				if (tile != null) {
					AnimatedTiledMapTile animatedTile = createAnimatedTile(tileSet, tile, tileElement, firstgid);
					if (animatedTile != null) {
						animatedTiles.add(animatedTile);
						tile = animatedTile;
					}
					addTileProperties(tile, tileElement);
					addTileObjectGroup(tile, tileElement);
				}
			}

			// replace original static tiles by animated tiles
			for (AnimatedTiledMapTile animatedTile : animatedTiles) {
				tileSet.putTile(animatedTile.getId(), animatedTile);
			}

			map.getTileSets().addTileSet(tileSet);
		}
	}

	protected abstract void addStaticTiles (FileHandle tmjFile, ImageResolver imageResolver, TiledMapTileSet tileSet,
		JsonValue element, JsonValue tiles, String name, int firstgid, int tilewidth, int tileheight, int spacing, int margin,
		String source, int offsetX, int offsetY, String imageSource, int imageWidth, int imageHeight, FileHandle image);

	private void addTileProperties (TiledMapTile tile, JsonValue tileElement) {
		String terrain = tileElement.getString("terrain", null);
		if (terrain != null) {
			tile.getProperties().put("terrain", terrain);
		}
		String probability = tileElement.getString("probability", null);
		if (probability != null) {
			tile.getProperties().put("probability", probability);
		}
		String type = tileElement.getString("type", null);
		if (type != null) {
			tile.getProperties().put("type", type);
		}
		JsonValue properties = tileElement.get("properties");
		if (properties != null) {
			loadProperties(tile.getProperties(), properties);
		}
	}

	private void addTileObjectGroup (TiledMapTile tile, JsonValue tileElement) {
		JsonValue objectgroupElement = tileElement.get("objectgroup");
		if (objectgroupElement != null) {
			for (JsonValue objectElement : objectgroupElement.get("objects")) {
				loadObject(this.map, tile, objectElement);
			}
		}
	}

	private AnimatedTiledMapTile createAnimatedTile (TiledMapTileSet tileSet, TiledMapTile tile, JsonValue tileElement,
		int firstgid) {
		JsonValue frames = tileElement.get("animation");
		if (frames != null) {
			Array<StaticTiledMapTile> staticTiles = new Array<>();
			IntArray intervals = new IntArray();
			for (JsonValue frameValue : frames.get("frame")) {
				staticTiles.add((StaticTiledMapTile)tileSet.getTile(firstgid + frameValue.getInt("tileid")));
				intervals.add(frameValue.getInt("duration"));
			}

			AnimatedTiledMapTile animatedTile = new AnimatedTiledMapTile(intervals, staticTiles);
			animatedTile.setId(tile.getId());
			return animatedTile;
		}
		return null;
	}

	protected void addStaticTiledMapTile (TiledMapTileSet tileSet, TextureRegion textureRegion, int tileId, float offsetX,
		float offsetY) {
		TiledMapTile tile = new StaticTiledMapTile(textureRegion);
		tile.setId(tileId);
		tile.setOffsetX(offsetX);
		tile.setOffsetY(flipY ? -offsetY : offsetY);
		tileSet.putTile(tileId, tile);
	}

	public static class Parameters extends AssetLoaderParameters<TiledMap> {
		/** generate mipmaps? **/
		public boolean generateMipMaps = false;
		/** The TextureFilter to use for minification **/
		public TextureFilter textureMinFilter = TextureFilter.Nearest;
		/** The TextureFilter to use for magnification **/
		public TextureFilter textureMagFilter = TextureFilter.Nearest;
		/** Whether to convert the objects' pixel position and size to the equivalent in tile space. **/
		public boolean convertObjectToTileSpace = false;
		/** Whether to flip all Y coordinates so that Y positive is up. All libGDX renderers require flipped Y coordinates, and thus
		 * flipY set to true. This parameter is included for non-rendering related purposes of tmj files, or custom renderers. */
		public boolean flipY = true;
	}
}
