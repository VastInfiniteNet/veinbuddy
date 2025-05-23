package com.github.sbobicus;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import org.apache.commons.lang3.StringUtils;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Scanner;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.PickaxeItem;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VeinBuddyClient implements ClientModInitializer {

   public static final Logger LOGGER = LoggerFactory.getLogger("veinbuddy");

   private final static MinecraftClient MC = MinecraftClient.getInstance();
   private final static double SPEED = 0.2f;
   private final static double RADIUS = 0.5;
   private final static double PLACE_RANGE = 6.0;
   private final static int MAX_TICKS = (int) (PLACE_RANGE / SPEED);
   private final static int DELAY = 5;
   private final static int MAX_DIG_RANGE_RADIUS = 16;
   private final static int DEFAULT_DIG_RANGE_RADIUS = 7;

   private Vec3i digRange = new Vec3i(DEFAULT_DIG_RANGE_RADIUS, DEFAULT_DIG_RANGE_RADIUS, DEFAULT_DIG_RANGE_RADIUS);

   private int selectionTicks = 0;
   private Vec3d pos = null;
   private Vec3i posBlock = null;
   private boolean change = true;
   private int saveNumber = 0;
   private int changeNumber = 0;
   private boolean showOutlines = false;
   private boolean render = true;

   private Set<Vec3i> selections = new ConcurrentSkipListSet<Vec3i>();
   private Map<Vec3i, Vec3i> selectionRanges = new ConcurrentHashMap<Vec3i, Vec3i>();
   private Map<Vec3i, Set<Vec3i>> selectionNeighbors = new ConcurrentHashMap<Vec3i, Set<Vec3i>>();

   private Map<Vec3i, WallGroup> selectionWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

   private Set<Vec3i> boundary = new ConcurrentSkipListSet<Vec3i>();
   private Set<Vec3i> wallBlocks = new ConcurrentSkipListSet<Vec3i>();
   private Map<Vec3i, WallGroup> wallBlockWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

   private ByteBuffer selectionBuffer = null;
   private ByteBuffer wallBuffer = null;
   private ByteBuffer gridBuffer = null;
   private boolean updateBuffers = true;

   private int vao = 0;
   private int selectionVBO = 0;
   private int wallVBO = 0;
   private int gridVBO = 0;

   private int selectionShaderProgram = 0;
   private int wallShaderProgram = 0;
   private int gridShaderProgram = 0;

   @Override
   public void onInitializeClient() {
      ClientLifecycleEvents.CLIENT_STARTED.register(this::loadShaders);
      ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onStart(client));
      ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
      ClientTickEvents.END_CLIENT_TICK.register(this::saveSelections);
      WorldRenderEvents.AFTER_TRANSLUCENT.register(this::afterTranslucent);
      WorldRenderEvents.LAST.register(this::wireframeOverlays);

      ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("veinbuddy")
                  .then(ClientCommandManager.literal("clearAll").executes(this::onClearAll))
                  .then(ClientCommandManager.literal("clearFar").executes(this::onClearFar))
                  .then(ClientCommandManager.literal("clearNear").executes(this::onClearNear))
                  .then(ClientCommandManager.literal("setDigRange")
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer(1, MAX_DIG_RANGE_RADIUS))
                              .then(ClientCommandManager.argument("y", IntegerArgumentType.integer(1, MAX_DIG_RANGE_RADIUS))
                                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer(1, MAX_DIG_RANGE_RADIUS))
                                          .executes(this::onSetDigRange)))))
                  .then(ClientCommandManager.literal("toggleOutlines").executes(this::onToggleOutlines))
                  .then(ClientCommandManager.literal("toggleRender").executes(this::onToggleRender))
      ));
   }

   private File getConfigFile(MinecraftClient client) {
      return new File(client.runDirectory, "config/veinbuddy.txt");
   }

   private File getSaveFile(MinecraftClient client) {
      ServerInfo serverInfo = client.getCurrentServerEntry();
      if (serverInfo == null)
         return null;
      String address = serverInfo.address;
      return new File(client.runDirectory, "data/veinbuddy/" + address + ".txt");
   }

   private void loadShaders(MinecraftClient client) {
      int selectionVertexShader = loadShaderProgram("selections", ".vsh", GL30.GL_VERTEX_SHADER);
      int wallVertexShader = loadShaderProgram("walls", ".vsh", GL30.GL_VERTEX_SHADER);
      int gridVertexShader = loadShaderProgram("grids", ".vsh", GL30.GL_VERTEX_SHADER);
      int identityFragmentShader = loadShaderProgram("identity", ".fsh", GL30.GL_FRAGMENT_SHADER);
      selectionShaderProgram = GL30.glCreateProgram();
      GL30.glAttachShader(selectionShaderProgram, selectionVertexShader);
      GL30.glAttachShader(selectionShaderProgram, identityFragmentShader);
      GL30.glLinkProgram(selectionShaderProgram);
      wallShaderProgram = GL30.glCreateProgram();
      GL30.glAttachShader(wallShaderProgram, wallVertexShader);
      GL30.glAttachShader(wallShaderProgram, identityFragmentShader);
      GL30.glLinkProgram(wallShaderProgram);
      gridShaderProgram = GL30.glCreateProgram();
      GL30.glAttachShader(gridShaderProgram, gridVertexShader);
      GL30.glAttachShader(gridShaderProgram, identityFragmentShader);
      GL30.glLinkProgram(gridShaderProgram);

      vao = GL30.glGenVertexArrays();
      selectionVBO = GL30.glGenBuffers();
      wallVBO = GL30.glGenBuffers();
      gridVBO = GL30.glGenBuffers();
   }

   private int loadShaderProgram(String name, String extension, int type) {
      try {
         boolean file_present = true;
         ResourceFactory resourceFactory = MinecraftClient.getInstance().getResourceManager();
         Optional<Resource> resource = resourceFactory
               .getResource(Identifier.of("renderer", "shader/" + name + extension));
         int i = GL30.glCreateShader(type);
         if (resource.isPresent()) {
            GL30.glShaderSource(i, readResourceAsString(resource.get().getInputStream()));
         } else
            file_present = false;
         GL30.glCompileShader(i);
         if (0 == GL30.glGetShaderi(i, GL30.GL_COMPILE_STATUS) || !file_present) {
            String shaderInfo = StringUtils.trim(GL30.glGetShaderInfoLog(i, 32768));
            throw new IOException("Couldn't compile " + name + extension + ": " + shaderInfo);
         }
         return i;
      } catch (IOException e) {
         e.printStackTrace();
      }
      return 0;
   }

   private String readResourceAsString(InputStream inputStream) {
      ByteBuffer byteBuffer = null;
      try {
         byteBuffer = TextureUtil.readResource(inputStream);
         int i = byteBuffer.position();
         byteBuffer.rewind();
         return MemoryUtil.memASCII(byteBuffer, i);
      } catch (IOException e) {
         LOGGER.error("Failed reading resource stream", e);
      } finally {
         if (byteBuffer != null) {
            MemoryUtil.memFree(byteBuffer);
         }
      }
      return null;
   }

   private void onStart(MinecraftClient client) {
      readConfigFile(client);
      readSaveFile(client);

      updateWalls();
      refreshBuffer();
   }


   private void readConfigFile(MinecraftClient client) {
      File configFile = getConfigFile(client);

      if (configFile == null || !configFile.exists()) {
         LOGGER.warn("No config file found.");
         return;
      }
      
      try (Scanner sc = new Scanner(configFile)) {
         int x = sc.nextInt();
         int y = sc.nextInt();
         int z = sc.nextInt();
         digRange = new Vec3i(x, y, z);
         LOGGER.info("Loaded config file.");
      } catch (IOException e) {
         LOGGER.error("Error reading config file", e);
      }
   }

   private void readSaveFile(MinecraftClient client) {
      File saveFile = getSaveFile(client);
      
      if (saveFile == null || !saveFile.exists()) {
         LOGGER.info("No save file found.");
         return;
      }
      try (Scanner sc = new Scanner(saveFile)) {
         int version = 1;
         if (!sc.hasNextInt()) {
            sc.next();
            if (sc.hasNextInt()) {
               version = sc.nextInt();
            }
         }
         sc.nextLine();
         if (version == 1) { // Version 1
            LOGGER.debug("Loading Version 1 selections save file.");
            while (sc.hasNext()) {
               int x = sc.nextInt();
               int y = sc.nextInt();
               int z = sc.nextInt();
               addSelection(new Vec3i(x, y, z), 
                  new Vec3i(DEFAULT_DIG_RANGE_RADIUS, DEFAULT_DIG_RANGE_RADIUS, DEFAULT_DIG_RANGE_RADIUS),
                  true);
               sc.nextLine();
            }
         } else if (version == 2) { // Version 2
            LOGGER.info("Loading Version 2 selections save file.");
            sc.nextLine();
            while (sc.hasNext()) {
               int x = sc.nextInt();
               int y = sc.nextInt();
               int z = sc.nextInt();
               int xRange = sc.nextInt();
               int yRange = sc.nextInt();
               int zRange = sc.nextInt();
               LOGGER.info(String.format("Loading selection %d %d %d, %d %d %d", 
                  x, y, z, xRange, yRange, zRange));
               addSelection(new Vec3i(x, y, z), new Vec3i(xRange, yRange, zRange), true);
               sc.nextLine();
            }
         } else {
            LOGGER.warn("Failed to load unsupported save file format.");
            return;
         }
         LOGGER.info("Loaded save file.");
      } catch (Exception e) {
         LOGGER.error("Failed to load selections save file.", e);
      }
   }

   private void saveConfigFile() {
      try (FileWriter fileWriter = new FileWriter(getConfigFile(MC), false)) {
         fileWriter.write(String.format("%d %d %d\n", 
            digRange.getX(), digRange.getY(), digRange.getZ()));
         LOGGER.info("Save new dig range to config file.");
      } catch (IOException e) {
         LOGGER.error("Failed to save dig range.", e);
      }
   }

   private void saveSelections(MinecraftClient client) {
      if (!(changeNumber > saveNumber))
         return;
      try {
         File saveFile = getSaveFile(client);
         if (saveFile == null)
            return;
         saveFile.getParentFile().mkdirs();
         FileWriter fileWriter = new FileWriter(saveFile, false);
         fileWriter.write("Version 2\n");
         for (Vec3i selection : selections) {
            Vec3i ranges = selectionRanges.get(selection);
            fileWriter.write(String.format(
               "%d %d %d %d %d %d\n", 
               selection.getX(), selection.getY(), selection.getZ(),
               ranges.getX(), ranges.getY(), ranges.getZ()
            ));
         }
         fileWriter.close();
         saveNumber = changeNumber;
         LOGGER.info("Saved selections to savefile.");
      } catch (IOException e) {
         LOGGER.error("Failed to save current selections", e);
      }
   }

   /**
    * Removes all drawn dig ranges and selected blocks in game.
    * @param ctx
    * @return
    */
   private int onClearAll(CommandContext<FabricClientCommandSource> ctx) {
      selections = new ConcurrentSkipListSet<Vec3i>();
      selectionWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

      boundary = new ConcurrentSkipListSet<Vec3i>();
      wallBlocks = new ConcurrentSkipListSet<Vec3i>();
      wallBlockWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

      changeNumber += 1;

      return 0;
   }

   /**
    * Removes drawn dig ranges player is located within. 
    * @param ctx
    * @return
    */
   private int onClearNear(CommandContext<FabricClientCommandSource> ctx) {
      onClear(true);
      return 0;
   }

   /**
    * Removes drawn dig ranges player is located outside of.
    * @param ctx
    * @return
    */
   private int onClearFar(CommandContext<FabricClientCommandSource> ctx) {
      onClear(false);
      return 0;
   }

   private static Vec3i find(Map<Vec3i, Vec3i> vecMap, Vec3i vec) {
      Vec3i parent = vecMap.get(vec);
      if (vec == parent)
         return parent;
      parent = find(vecMap, parent);
      vecMap.put(vec, parent);
      return parent;
   }

   private void onClear(boolean clearNear) {
      Vec3d pos = MC.player.getPos();
      posBlock = new Vec3i((int) Math.floor(pos.getX()),
            (int) Math.floor(pos.getY()),
            (int) Math.floor(pos.getZ()));

      Map<Vec3i, Vec3i> selectionGroup = new HashMap<Vec3i, Vec3i>();
      Set<Vec3i> nearSelections = new HashSet<Vec3i>();

      for (Vec3i selection : selections) {
         Vec3i distance = selection.subtract(posBlock);
         Vec3i range = selectionRanges.get(selection);
         if (Math.abs(distance.getX()) <= range.getX() &&
               Math.abs(distance.getY()) <= range.getY() &&
               Math.abs(distance.getZ()) <= range.getZ())
            nearSelections.add(selection);
      }

      for (Vec3i selection : selections) {
         selectionGroup.put(selection, selection);
      }
      for (Vec3i selection : selections) {
         Set<Vec3i> neighbors = selectionNeighbors.get(selection);
         for (Vec3i neighbor : neighbors) {
            Vec3i group = find(selectionGroup, selection);
            Vec3i neighborGroup = find(selectionGroup, neighbor);
            selectionGroup.put(group, neighborGroup);
         }
      }
      Set<Vec3i> nearSelectionsGroups = new HashSet<Vec3i>();
      for (Vec3i selection : nearSelections) {
         nearSelectionsGroups.add(find(selectionGroup, selection));
      }
      for (Vec3i selection : selections) {
         Vec3i targetGroup = find(selectionGroup, selection);
         if (clearNear == nearSelectionsGroups.contains(targetGroup)) {
            removeSelection(selection, true);
         }
      }
      changeNumber += 1;
      updateWalls();
      refreshBuffer();
   }

   /**
    * Shows ___ if disabled, hides if already enabled.
    * @param ctx
    * @return
    */
   private int onToggleOutlines(CommandContext<FabricClientCommandSource> ctx) {
      showOutlines = !showOutlines;
      return 0;
   }

   /**
    * Disables are veinbuddy related ingame drawnings.
    * @param ctx
    * @return
    */
   private int onToggleRender(CommandContext<FabricClientCommandSource> ctx) {
      render = !render;
      return 0;
   }

   /**
    * Sets the size of the dig range when selecting an ore focus block.
    * @param ctx
    * @return
    */
   private int onSetDigRange(CommandContext<FabricClientCommandSource> ctx) {
      int x = IntegerArgumentType.getInteger(ctx, "x");
      int y = IntegerArgumentType.getInteger(ctx, "y");
      int z = IntegerArgumentType.getInteger(ctx, "z");
      digRange = new Vec3i(x, y, z);
      saveConfigFile();

      return 0;
   }

   private void onTick(MinecraftClient client) {
      if (client.player == null 
            || client.mouse == null 
            || client.world == null)
         return;
      if (!(client.player.getInventory().getMainHandStack().getItem() instanceof PickaxeItem)) {
         pos = null;
         posBlock = null;
         selectionTicks = 0;
         return;
      }
      boolean rightClick = client.mouse.wasRightButtonClicked();
      Vec3d playerPos = client.player.getPos().add(0.0f, 1.6f, 0.0f);
      Vec3d playerDir = client.player.getRotationVector();
      if (!rightClick && 0 != selectionTicks && 10 > selectionTicks) {
         removeLookedAtSelection(playerPos, playerDir);
      }
      if (!rightClick && 10 <= selectionTicks) {
         addSelection(posBlock, digRange, false);
      }
      if (!rightClick) {
         pos = null;
         posBlock = null;
      }
      selectionTicks = rightClick ? selectionTicks + 1 : 0;
      selectionTicks = Math.min(selectionTicks, MAX_TICKS + DELAY);
      if (10 > selectionTicks)
         return;
      pos = playerPos.add(playerDir.multiply(SPEED).multiply(selectionTicks - DELAY));
      posBlock = new Vec3i((int) Math.floor(pos.getX()),
            (int) Math.floor(pos.getY()),
            (int) Math.floor(pos.getZ()));
   }

   private boolean rayIntersectsSphere(Vec3d orig, Vec3d rot, Vec3d center) {
      Vec3d L = orig.subtract(center);
      double a = rot.dotProduct(rot);
      double b = 2.0 * rot.dotProduct(L);
      double c = L.dotProduct(L) - RADIUS * RADIUS;
      double discr = b * b - 4.0 * a * c;
      if (discr < 0)
         return false;
      double q = (b > 0.0) ? -.5 * (b + Math.sqrt(discr)) : -.5 * (b - Math.sqrt(discr));
      double t0 = q / a;
      double t1 = c / q;
      if (t0 < 0 && t1 < 0)
         return false;
      return true;
   }

   private boolean rayIntersectsXFace(Vec3d orig, Vec3d rot, Vec3i block) {
      double minX = (double) block.getX();
      double minY = (double) block.getY();
      double minZ = (double) block.getZ();
      double maxX = minX + 1.0;
      double maxY = minY + 1.0;
      double maxZ = minZ + 1.0;
      if (0.0 == rot.getX())
         return false;
      double nearX = (minX > orig.getX()) ? minX : maxX;
      double tX = (nearX - orig.getX()) / rot.getX();
      Vec3d iPos = orig.add(rot.multiply(tX));
      return minY <= iPos.getY() && iPos.getY() <= maxY && minZ <= iPos.getZ() && iPos.getZ() <= maxZ;
   }

   private boolean rayIntersectsYFace(Vec3d orig, Vec3d rot, Vec3i block) {
      double minX = (double) block.getX();
      double minY = (double) block.getY();
      double minZ = (double) block.getZ();
      double maxX = minX + 1.0;
      double maxY = minY + 1.0;
      double maxZ = minZ + 1.0;
      if (0.0 == rot.getY())
         return false;
      double nearY = (minY > orig.getY()) ? minY : maxY;
      double tY = (nearY - orig.getY()) / rot.getY();
      Vec3d iPos = orig.add(rot.multiply(tY));
      return minX <= iPos.getX() && iPos.getX() <= maxX && minZ <= iPos.getZ() && iPos.getZ() <= maxZ;
   }

   private boolean rayIntersectsZFace(Vec3d orig, Vec3d rot, Vec3i block) {
      double minX = (double) block.getX();
      double minY = (double) block.getY();
      double minZ = (double) block.getZ();
      double maxX = minX + 1.0;
      double maxY = minY + 1.0;
      double maxZ = minZ + 1.0;
      if (0.0 == rot.getZ())
         return false;
      double nearZ = (minZ > orig.getZ()) ? minZ : maxZ;
      double tZ = (nearZ - orig.getZ()) / rot.getZ();
      Vec3d iPos = orig.add(rot.multiply(tZ));
      return minX <= iPos.getX() && iPos.getX() <= maxX && minY <= iPos.getY() && iPos.getY() <= maxY;
   }

   private void addSelection(Vec3i selection, Vec3i range, boolean bulk) {
      if (selections.contains(selection))
         return;

      Set<Vec3i> neighbors = new ConcurrentSkipListSet<Vec3i>();
      for (Vec3i neighbor : selections) {
         Vec3i neighborRange = selectionRanges.get(neighbor);
         Vec3i distances = selection.subtract(neighbor);
         if (Math.abs(distances.getX()) <= (neighborRange.getX() + range.getX()) &&
               Math.abs(distances.getY()) <= (neighborRange.getY() + range.getY()) &&
               Math.abs(distances.getZ()) <= (neighborRange.getZ() + range.getZ()))
            ;
         neighbors.add(neighbor);
      }

      Set<Vec3i> potentialWallBlocks = new HashSet<Vec3i>();
      int digXRange = range.getX();
      int digYRange = range.getY();
      int digZRange = range.getZ();
      int boundaryXRange = range.getX() + 1;
      int boundaryYRange = range.getY() + 1;
      int boundaryZRange = range.getZ() + 1;

      for (int x = -1 * boundaryXRange; x <= boundaryXRange; ++x) {
         for (int y = -1 * boundaryYRange; y <= boundaryYRange; ++y) {
            for (int z = -1 * boundaryZRange; z <= boundaryZRange; ++z) {
               Vec3i block = selection.add(x, y, z);
               boolean xWall = Math.abs(x) == digXRange;
               boolean yWall = Math.abs(y) == digYRange;
               boolean zWall = Math.abs(z) == digZRange;
               boolean xBoundary = Math.abs(x) == boundaryXRange;
               boolean yBoundary = Math.abs(y) == boundaryYRange;
               boolean zBoundary = Math.abs(z) == boundaryZRange;
               boolean pBoundary = xBoundary || yBoundary || zBoundary;
               if ((xWall || yWall || zWall) && !pBoundary)
                  potentialWallBlocks.add(block);
               if (pBoundary) {
                  for (Vec3i neighbor : neighbors) {
                     Vec3i neighborRange = selectionRanges.get(neighbor);
                     int neighborXLeast = neighbor.getX() - neighborRange.getX();
                     int neighborYLeast = neighbor.getY() - neighborRange.getY();
                     int neighborZLeast = neighbor.getZ() - neighborRange.getZ();
                     int neighborXMost = neighbor.getX() + neighborRange.getX();
                     int neighborYMost = neighbor.getY() + neighborRange.getY();
                     int neighborZMost = neighbor.getZ() + neighborRange.getZ();
                     pBoundary = pBoundary && !(neighborXLeast <= block.getX() && block.getX() <= neighborXMost &&
                           neighborYLeast <= block.getY() && block.getY() <= neighborYMost &&
                           neighborZLeast <= block.getZ() && block.getZ() <= neighborZMost);
                  }
               }
               if (pBoundary)
                  boundary.add(block);
               else
                  boundary.remove(block);
            }
         }
      }

      for (Vec3i block : potentialWallBlocks) {
         boolean pWest = boundary.contains(block.add(-1, 0, 0));
         boolean pEast = boundary.contains(block.add(1, 0, 0));
         boolean pDown = boundary.contains(block.add(0, -1, 0));
         boolean pUp = boundary.contains(block.add(0, 1, 0));
         boolean pSouth = boundary.contains(block.add(0, 0, -1));
         boolean pNorth = boundary.contains(block.add(0, 0, 1));
         if (pWest || pEast || pDown || pUp || pSouth || pNorth)
            wallBlocks.add(block);
      }

      float minX = selection.getX();
      float minY = selection.getY();
      float minZ = selection.getZ();
      float maxX = minX + 1.0f;
      float maxY = minY + 1.0f;
      float maxZ = minZ + 1.0f;
      float adjustment = 0.01f;
      minX -= adjustment;
      minY -= adjustment;
      minZ -= adjustment;
      maxX += adjustment;
      maxY += adjustment;
      maxZ += adjustment;
      WallGroup group = new WallGroup(true, true, true, true, true, true);
      group.putVertex(new Vector3f(minX, minY, minZ), false, false, false);
      group.putVertex(new Vector3f(minX, minY, maxZ), false, false, true);
      group.putVertex(new Vector3f(minX, maxY, minZ), false, true, false);
      group.putVertex(new Vector3f(minX, maxY, maxZ), false, true, true);
      group.putVertex(new Vector3f(maxX, minY, minZ), true, false, false);
      group.putVertex(new Vector3f(maxX, minY, maxZ), true, false, true);
      group.putVertex(new Vector3f(maxX, maxY, minZ), true, true, false);
      group.putVertex(new Vector3f(maxX, maxY, maxZ), true, true, true);

      selectionWalls.put(selection, group);
      selectionNeighbors.put(selection, neighbors);
      for (Vec3i neighbor : neighbors) {
         Set<Vec3i> neighborNeighbors = selectionNeighbors.get(neighbor);
         neighborNeighbors.add(selection);
      }
      selectionRanges.put(selection, range);
      selections.add(selection);

      if (!bulk) {
         changeNumber += 1;
         updateWalls();
         refreshBuffer();
      }
   }

   private void removeLookedAtSelection(Vec3d pos, Vec3d dir) {
      for (int i = 0; i < 2.0 * MAX_TICKS; ++i) {
         Vec3i block = new Vec3i((int) Math.floor(pos.getX()),
               (int) Math.floor(pos.getY()),
               (int) Math.floor(pos.getZ()));
         if (selections.contains(block)) {
            removeSelection(block, false);
            return;
         }
         pos = pos.add(dir.multiply(0.2));
      }
   }

   private void removeSelection(Vec3i selection, boolean bulk) {
      Vec3i range = selectionRanges.get(selection);
      Set<Vec3i> neighbors = selectionNeighbors.get(selection);

      for (Vec3i neighbor : neighbors) {
         Set<Vec3i> neighborNeighbors = selectionNeighbors.get(neighbor);
         neighborNeighbors.remove(selection);
      }

      selectionWalls.remove(selection);
      selectionNeighbors.remove(selection);
      selectionRanges.remove(selection);
      selections.remove(selection);

      Set<Vec3i> caught = new HashSet<Vec3i>();
      Set<Vec3i> orphaned = new HashSet<Vec3i>();
      int boundXRange = range.getX() + 1;
      int boundYRange = range.getY() + 1;
      int boundZRange = range.getZ() + 1;
      for (int x = -1 * boundXRange; x <= boundXRange; ++x) {
         for (int y = -1 * boundYRange; y <= boundYRange; ++y) {
            for (int z = -1 * boundZRange; z <= boundZRange; ++z) {
               Vec3i block = selection.add(x, y, z);
               wallBlocks.remove(block);
               wallBlockWalls.remove(block);
               boundary.remove(block);
               boolean pCaught = false;
               for (Vec3i neighbor : neighbors) {
                  Vec3i neighborRange = selectionRanges.get(neighbor);
                  int neighborXLeast = neighbor.getX() - neighborRange.getX();
                  int neighborYLeast = neighbor.getY() - neighborRange.getY();
                  int neighborZLeast = neighbor.getZ() - neighborRange.getZ();
                  int neighborXMost = neighbor.getX() + neighborRange.getX();
                  int neighborYMost = neighbor.getY() + neighborRange.getY();
                  int neighborZMost = neighbor.getZ() + neighborRange.getZ();
                  pCaught = pCaught || (neighborXLeast <= block.getX() && block.getX() <= neighborXMost &&
                        neighborYLeast <= block.getY() && block.getY() <= neighborYMost &&
                        neighborZLeast <= block.getZ() && block.getZ() <= neighborZMost);
               }
               if (pCaught)
                  caught.add(block);
               else
                  orphaned.add(block);
            }
         }
      }
      for (Vec3i block : orphaned) {
         Vec3i vWest = block.add(-1, 0, 0);
         Vec3i vEast = block.add(1, 0, 0);
         Vec3i vDown = block.add(0, -1, 0);
         Vec3i vUp = block.add(0, 1, 0);
         Vec3i vSouth = block.add(0, 0, -1);
         Vec3i vNorth = block.add(0, 0, 1);
         boolean pWest = wallBlocks.contains(vWest) || caught.contains(vWest);
         boolean pEast = wallBlocks.contains(vEast) || caught.contains(vEast);
         boolean pDown = wallBlocks.contains(vDown) || caught.contains(vDown);
         boolean pUp = wallBlocks.contains(vUp) || caught.contains(vUp);
         boolean pSouth = wallBlocks.contains(vSouth) || caught.contains(vSouth);
         boolean pNorth = wallBlocks.contains(vNorth) || caught.contains(vNorth);
         if (pWest || pEast || pDown || pUp || pSouth || pNorth)
            boundary.add(block);
      }
      for (Vec3i block : caught) {
         boolean pWest = boundary.contains(block.add(-1, 0, 0));
         boolean pEast = boundary.contains(block.add(1, 0, 0));
         boolean pDown = boundary.contains(block.add(0, -1, 0));
         boolean pUp = boundary.contains(block.add(0, 1, 0));
         boolean pSouth = boundary.contains(block.add(0, 0, -1));
         boolean pNorth = boundary.contains(block.add(0, 0, 1));
         if (pWest || pEast || pDown || pUp || pSouth || pNorth)
            wallBlocks.add(block);
      }
      if (!bulk) {
         changeNumber += 1;
         updateWalls();
         refreshBuffer();
      }
   }

   private void updateWalls() {
      for (Vec3i block : wallBlocks) {
         WallGroup group = updateWall(block);
         wallBlockWalls.put(block, group);
      }
   }

   private WallGroup updateWall(Vec3i block) {
      boolean pWest = boundary.contains(block.add(-1, 0, 0));
      boolean pEast = boundary.contains(block.add(1, 0, 0));
      boolean pDown = boundary.contains(block.add(0, -1, 0));
      boolean pUp = boundary.contains(block.add(0, 1, 0));
      boolean pSouth = boundary.contains(block.add(0, 0, -1));
      boolean pNorth = boundary.contains(block.add(0, 0, 1));

      if ((pWest && pEast) || (pDown && pUp) || (pSouth && pNorth))
         LOGGER.warn("Error!");

      float minX = block.getX();
      float minY = block.getY();
      float minZ = block.getZ();
      float maxX = minX + 1.0f;
      float maxY = minY + 1.0f;
      float maxZ = minZ + 1.0f;
      float adjustment = 0.01f;

      float maxXAdjusted = maxX;
      float maxYAdjusted = maxY;
      float maxZAdjusted = maxZ;
      float minXAdjusted = minX;
      float minYAdjusted = minY;
      float minZAdjusted = minZ;

      float xVal = minX + adjustment;
      float yVal = minY + adjustment;
      float zVal = minZ + adjustment;
      int xBlock = -1;
      int yBlock = -1;
      int zBlock = -1;

      if (pEast) {
         xVal = maxX - adjustment;
         xBlock = 1;
      }
      if (pUp) {
         yVal = maxY - adjustment;
         yBlock = 1;
      }
      if (pNorth) {
         zVal = maxZ - adjustment;
         zBlock = 1;
      }

      if (pWest || pEast) {
         boolean upNorth = !boundary.contains(block.add(xBlock, 1, 1));
         boolean up = !boundary.contains(block.add(xBlock, 1, 0));
         boolean upSouth = !boundary.contains(block.add(xBlock, 1, -1));

         boolean north = !boundary.contains(block.add(xBlock, 0, 1));
         boolean neutral = !boundary.contains(block.add(xBlock, 0, 0));
         boolean south = !boundary.contains(block.add(xBlock, 0, -1));

         boolean downNorth = !boundary.contains(block.add(xBlock, -1, 1));
         boolean down = !boundary.contains(block.add(xBlock, -1, 0));
         boolean downSouth = !boundary.contains(block.add(xBlock, -1, -1));

         boolean upNorthCorner = upNorth && !up && !north;
         boolean upSouthCorner = upSouth && !up && !south;
         boolean downNorthCorner = downNorth && !down && !north;
         boolean downSouthCorner = downSouth && !down && !south;

         if ((!neutral && up) || upNorthCorner || upSouthCorner)
            maxYAdjusted = maxY + adjustment;
         if ((!neutral && down) || downNorthCorner || downSouthCorner)
            minYAdjusted = minY - adjustment;
         if ((!neutral && north) || upNorthCorner || downNorthCorner)
            maxZAdjusted = maxZ + adjustment;
         if ((!neutral && south) || upSouthCorner || downSouthCorner)
            minZAdjusted = minZ - adjustment;

         if ((!upSouth && south) || (!upNorth && north))
            maxYAdjusted = maxY - adjustment;
         if ((!downSouth && south) || (!downNorth && north))
            minYAdjusted = minY + adjustment;
         if ((!upNorth && up) || (!downNorth && down))
            maxZAdjusted = maxZ - adjustment;
         if ((!upSouth && up) || (!downSouth && down))
            minZAdjusted = minZ + adjustment;
      }

      if (pDown || pUp) {
         boolean northEast = !boundary.contains(block.add(1, yBlock, 1));
         boolean north = !boundary.contains(block.add(0, yBlock, 1));
         boolean northWest = !boundary.contains(block.add(-1, yBlock, 1));

         boolean east = !boundary.contains(block.add(1, yBlock, 0));
         boolean neutral = !boundary.contains(block.add(0, yBlock, 0));
         boolean west = !boundary.contains(block.add(-1, yBlock, 0));

         boolean southEast = !boundary.contains(block.add(1, yBlock, -1));
         boolean south = !boundary.contains(block.add(0, yBlock, -1));
         boolean southWest = !boundary.contains(block.add(-1, yBlock, -1));

         boolean northEastCorner = northEast && !north && !east;
         boolean northWestCorner = northWest && !north && !west;
         boolean southEastCorner = southEast && !south && !east;
         boolean southWestCorner = southWest && !south && !west;

         if ((!neutral && east) || northEastCorner || southEastCorner)
            maxXAdjusted = maxX + adjustment;
         if ((!neutral && west) || northWestCorner || southWestCorner)
            minXAdjusted = minX - adjustment;
         if ((!neutral && north) || northEastCorner || northWestCorner)
            maxZAdjusted = maxZ + adjustment;
         if ((!neutral && south) || southEastCorner || southWestCorner)
            minZAdjusted = minZ - adjustment;

         if ((!northEast && north) || (!southEast && south))
            maxXAdjusted = maxX - adjustment;
         if ((!northWest && north) || (!southWest && south))
            minXAdjusted = minX + adjustment;
         if ((!northEast && east) || (!northWest && west))
            maxZAdjusted = maxZ - adjustment;
         if ((!southEast && east) || (!southWest && west))
            minZAdjusted = minZ + adjustment;
      }

      if (pSouth || pNorth) {
         boolean upEast = !boundary.contains(block.add(1, 1, zBlock));
         boolean up = !boundary.contains(block.add(0, 1, zBlock));
         boolean upWest = !boundary.contains(block.add(-1, 1, zBlock));

         boolean east = !boundary.contains(block.add(1, 0, zBlock));
         boolean neutral = !boundary.contains(block.add(0, 0, zBlock));
         boolean west = !boundary.contains(block.add(-1, 0, zBlock));

         boolean downEast = !boundary.contains(block.add(1, -1, zBlock));
         boolean down = !boundary.contains(block.add(0, -1, zBlock));
         boolean downWest = !boundary.contains(block.add(-1, -1, zBlock));

         boolean upEastCorner = upEast && !up && !east;
         boolean upWestCorner = upWest && !up && !west;
         boolean downEastCorner = downEast && !down && !east;
         boolean downWestCorner = downWest && !down && !west;

         if ((!neutral && east) || upEastCorner || downEastCorner)
            maxXAdjusted = maxX + adjustment;
         if ((!neutral && west) || upWestCorner || downWestCorner)
            minXAdjusted = minX - adjustment;
         if ((!neutral && up) || upEastCorner || upWestCorner)
            maxYAdjusted = maxY + adjustment;
         if ((!neutral && down) || downEastCorner || downWestCorner)
            minYAdjusted = minY - adjustment;

         if ((!upEast && up) || (!downEast && down))
            maxXAdjusted = maxX - adjustment;
         if ((!upWest && up) || (!downWest && down))
            minXAdjusted = minX + adjustment;
         if ((!upEast && east) || (!upWest && west))
            maxYAdjusted = maxY - adjustment;
         if ((!downEast && east) || (!downWest && west))
            minYAdjusted = minY + adjustment;
      }

      WallGroup wallGroup = new WallGroup(pWest, pEast, pDown, pUp, pSouth, pNorth);

      if (pWest || pEast) {
         wallGroup.putVertex(new Vector3f(xVal, minYAdjusted, minZAdjusted), pEast, false, false);
         wallGroup.putVertex(new Vector3f(xVal, minYAdjusted, maxZAdjusted), pEast, false, true);
         wallGroup.putVertex(new Vector3f(xVal, maxYAdjusted, minZAdjusted), pEast, true, false);
         wallGroup.putVertex(new Vector3f(xVal, maxYAdjusted, maxZAdjusted), pEast, true, true);
      }

      if (pDown || pUp) {
         wallGroup.putVertex(new Vector3f(minXAdjusted, yVal, minZAdjusted), false, pUp, false);
         wallGroup.putVertex(new Vector3f(minXAdjusted, yVal, maxZAdjusted), false, pUp, true);
         wallGroup.putVertex(new Vector3f(maxXAdjusted, yVal, minZAdjusted), true, pUp, false);
         wallGroup.putVertex(new Vector3f(maxXAdjusted, yVal, maxZAdjusted), true, pUp, true);
      }

      if (pSouth || pNorth) {
         wallGroup.putVertex(new Vector3f(minXAdjusted, minYAdjusted, zVal), false, false, pNorth);
         wallGroup.putVertex(new Vector3f(minXAdjusted, maxYAdjusted, zVal), false, true, pNorth);
         wallGroup.putVertex(new Vector3f(maxXAdjusted, minYAdjusted, zVal), true, false, pNorth);
         wallGroup.putVertex(new Vector3f(maxXAdjusted, maxYAdjusted, zVal), true, true, pNorth);
      }

      if ((pWest || pEast) && (pDown || pUp)) {
         wallGroup.putVertex(new Vector3f(xVal, yVal, minZAdjusted), pEast, pUp, false);
         wallGroup.putVertex(new Vector3f(xVal, yVal, maxZAdjusted), pEast, pUp, true);
      }
      if ((pWest || pEast) && (pSouth || pNorth)) {
         wallGroup.putVertex(new Vector3f(xVal, minYAdjusted, zVal), pEast, false, pNorth);
         wallGroup.putVertex(new Vector3f(xVal, maxYAdjusted, zVal), pEast, true, pNorth);
      }
      if ((pDown || pUp) && (pSouth || pNorth)) {
         wallGroup.putVertex(new Vector3f(minXAdjusted, yVal, zVal), false, pUp, pNorth);
         wallGroup.putVertex(new Vector3f(maxXAdjusted, yVal, zVal), true, pUp, pNorth);
      }

      if ((pWest || pEast) && (pDown || pUp) && (pSouth || pNorth)) {
         wallGroup.putVertex(new Vector3f(xVal, yVal, zVal), pEast, pUp, pNorth);
      }

      return wallGroup;
   }

   private void wireframeOverlays(WorldRenderContext ctx) {
      if (posBlock == null && (!showOutlines || selections.isEmpty()) 
            || MC.player == null)
         return;

      Vec3d camPos = ctx.camera().getPos();
      MatrixStack stack = ctx.matrixStack();
      stack.push();
      stack.translate(-camPos.getX(), -camPos.getY(), -camPos.getZ());
      Matrix4f mat = stack.peek().getPositionMatrix();
      Tessellator tessellator = Tessellator.getInstance();
      BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

      if (posBlock != null) {
         buildVerticesOutline(buffer, mat, posBlock);
      }

      if (showOutlines && !selections.isEmpty()) {
         for (Vec3i selection : selections) {
            buildVerticesOutline(buffer, mat, selection);
         }
      }

      RenderSystem.setShader(GameRenderer::getPositionColorProgram);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

      BufferRenderer.drawWithGlobalProgram(buffer.end());

      stack.pop();
   }

   private void refreshBuffer() {
      if (selections.isEmpty())
         return;
      int numWalls = 0;
      for (Vec3i wallBlock : wallBlocks) {
         WallGroup group = wallBlockWalls.get(wallBlock);
         numWalls += group.getSize();
      }
      selectionBuffer = BufferUtils.createByteBuffer(selections.size() * 6 * 6 * 3 * 4); // six walls, six vertices per
                                                                                         // wall, three floats per
                                                                                         // vertex,
                                                                                         // four bytes per float
      wallBuffer = BufferUtils.createByteBuffer(numWalls * 6 * 3 * 4); // six vertices per wall, three floats per
                                                                       // vertex,
                                                                       // four bytes per float
      gridBuffer = BufferUtils.createByteBuffer(numWalls * 8 * 3 * 4); // eight vertices per wall, three floats per
                                                                       // vertex, four bytes per float

      for (Vec3i selection : selections) {
         WallGroup group = selectionWalls.get(selection);
         buildVerticesSelection(selectionBuffer, group);
      }

      for (Vec3i wallBlock : wallBlocks) {
         WallGroup group = wallBlockWalls.get(wallBlock);
         buildVerticesWallBlockGroup(wallBuffer, group);
         buildVerticesWallBlockGroupOutline(gridBuffer, group);
      }
      // flip buffers for reading
      selectionBuffer.flip();
      wallBuffer.flip();
      gridBuffer.flip();
      updateBuffers = true;
   }

   private void afterTranslucent(WorldRenderContext ctx) {
      if (selections.isEmpty() || !render)
         return;

      Vec3d camPos = ctx.camera().getPos();
      Vector3f camVec = new Vector3f(-(float) camPos.getX(), -(float) camPos.getY(), -(float) camPos.getZ());
      Quaternionf camQuat = ctx.camera().getRotation().invert();

      FloatBuffer mat = (new Matrix4f(ctx.projectionMatrix())).rotate(camQuat).translate(camVec)
            .get(BufferUtils.createFloatBuffer(16));

      RenderSystem.enableBlend();
      RenderSystem.disableCull();

      if (updateBuffers) {
         GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, selectionVBO);
         GL30.glBufferData(GL30.GL_ARRAY_BUFFER, selectionBuffer, GL30.GL_STATIC_DRAW);
         GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, wallVBO);
         GL30.glBufferData(GL30.GL_ARRAY_BUFFER, wallBuffer, GL30.GL_STATIC_DRAW);
         GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, gridVBO);
         GL30.glBufferData(GL30.GL_ARRAY_BUFFER, gridBuffer, GL30.GL_STATIC_DRAW);
      }

      GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, selectionVBO);
      GL30.glBindVertexArray(vao);
      GL30.glEnableVertexAttribArray(0);
      GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);

      GL30.glUseProgram(selectionShaderProgram);
      GL30.glUniformMatrix4fv(GL30.glGetUniformLocation(selectionShaderProgram, "u_projection"), false, mat);
      GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, selectionBuffer.capacity() / 3 / 4); // three floats per vertex
                                                                                   // four bytes per float
      GL30.glUseProgram(0);

      GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, wallVBO);
      GL30.glBindVertexArray(vao);
      GL30.glEnableVertexAttribArray(0);
      GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);

      GL30.glUseProgram(wallShaderProgram);
      GL30.glUniformMatrix4fv(GL30.glGetUniformLocation(wallShaderProgram, "u_projection"), false, mat);
      GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, wallBuffer.capacity() / 3 / 4); // three floats per vertex
                                                                              // four bytes per float
      GL30.glUseProgram(0);

      GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, gridVBO);
      GL30.glBindVertexArray(vao);
      GL30.glEnableVertexAttribArray(0);
      GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);

      GL30.glUseProgram(gridShaderProgram);
      GL30.glUniformMatrix4fv(GL30.glGetUniformLocation(gridShaderProgram, "u_projection"), false, mat);
      GL30.glDrawArrays(GL30.GL_LINES, 0, gridBuffer.capacity() / 3 / 4); // three vertices per vertex
                                                                          // four bytes per float
      GL30.glUseProgram(0);

      updateBuffers = false;

      RenderSystem.disableBlend();
      RenderSystem.enableCull();
   }

   private void buildVerticesSelection(ByteBuffer vertices, WallGroup group) {
      buildVerticesWall(vertices, group.getWestWall());
      buildVerticesWall(vertices, group.getEastWall());
      buildVerticesWall(vertices, group.getDownWall());
      buildVerticesWall(vertices, group.getUpWall());
      buildVerticesWall(vertices, group.getSouthWall());
      buildVerticesWall(vertices, group.getNorthWall());
   }

   private void buildVerticesWallBlockGroup(ByteBuffer vertices, WallGroup group) {
      WallVertexGroup west, east, down, up, south, north;
      west = group.getWestWall();
      east = group.getEastWall();
      down = group.getDownWall();
      up = group.getUpWall();
      south = group.getSouthWall();
      north = group.getNorthWall();

      if (west != null)
         buildVerticesWall(vertices, west);
      if (east != null)
         buildVerticesWall(vertices, east);
      if (down != null)
         buildVerticesWall(vertices, down);
      if (up != null)
         buildVerticesWall(vertices, up);
      if (south != null)
         buildVerticesWall(vertices, south);
      if (north != null)
         buildVerticesWall(vertices, north);
   }

   private void buildVerticesWall(ByteBuffer vertices, WallVertexGroup g) {
      vertices.putFloat(g.v00.x);
      vertices.putFloat(g.v00.y);
      vertices.putFloat(g.v00.z);

      vertices.putFloat(g.v01.x);
      vertices.putFloat(g.v01.y);
      vertices.putFloat(g.v01.z);

      vertices.putFloat(g.v11.x);
      vertices.putFloat(g.v11.y);
      vertices.putFloat(g.v11.z);

      vertices.putFloat(g.v00.x);
      vertices.putFloat(g.v00.y);
      vertices.putFloat(g.v00.z);

      vertices.putFloat(g.v10.x);
      vertices.putFloat(g.v10.y);
      vertices.putFloat(g.v10.z);

      vertices.putFloat(g.v11.x);
      vertices.putFloat(g.v11.y);
      vertices.putFloat(g.v11.z);
   }

   private void buildVerticesWallBlockGroupOutline(ByteBuffer vertices, WallGroup group) {
      WallVertexGroup west, east, down, up, south, north;

      west = group.getWestWall();
      east = group.getEastWall();
      down = group.getDownWall();
      up = group.getUpWall();
      south = group.getSouthWall();
      north = group.getNorthWall();

      int walls = 0;
      if (west != null)
         buildVerticesWallOutline(vertices, west);
      if (east != null)
         buildVerticesWallOutline(vertices, east);
      if (down != null)
         buildVerticesWallOutline(vertices, down);
      if (up != null)
         buildVerticesWallOutline(vertices, up);
      if (south != null)
         buildVerticesWallOutline(vertices, south);
      if (north != null)
         buildVerticesWallOutline(vertices, north);
   }

   private void buildVerticesWallOutline(ByteBuffer vertices, WallVertexGroup g) {
      vertices.putFloat(g.v00.x);
      vertices.putFloat(g.v00.y);
      vertices.putFloat(g.v00.z);

      vertices.putFloat(g.v01.x);
      vertices.putFloat(g.v01.y);
      vertices.putFloat(g.v01.z);

      vertices.putFloat(g.v01.x);
      vertices.putFloat(g.v01.y);
      vertices.putFloat(g.v01.z);

      vertices.putFloat(g.v11.x);
      vertices.putFloat(g.v11.y);
      vertices.putFloat(g.v11.z);

      vertices.putFloat(g.v11.x);
      vertices.putFloat(g.v11.y);
      vertices.putFloat(g.v11.z);

      vertices.putFloat(g.v10.x);
      vertices.putFloat(g.v10.y);
      vertices.putFloat(g.v10.z);

      vertices.putFloat(g.v10.x);
      vertices.putFloat(g.v10.y);
      vertices.putFloat(g.v10.z);

      vertices.putFloat(g.v00.x);
      vertices.putFloat(g.v00.y);
      vertices.putFloat(g.v00.z);
   }

   private void buildVerticesOutline(BufferBuilder buffer, Matrix4f mat, Vec3i block) {
      float minX = (float) block.getX();
      float minY = (float) block.getY();
      float minZ = (float) block.getZ();

      float maxX = minX + 1.0f;
      float maxY = minY + 1.0f;
      float maxZ = minZ + 1.0f;

      buffer.vertex(mat, minX, minY, minZ).color(0xFF000000);
      buffer.vertex(mat, minX, minY, maxZ).color(0xFF000000);
      buffer.vertex(mat, minX, minY, maxZ).color(0xFF000000);
      buffer.vertex(mat, maxX, minY, maxZ).color(0xFF000000);
      buffer.vertex(mat, maxX, minY, maxZ).color(0xFF000000);
      buffer.vertex(mat, maxX, minY, minZ).color(0xFF000000);
      buffer.vertex(mat, maxX, minY, minZ).color(0xFF000000);
      buffer.vertex(mat, minX, minY, minZ).color(0xFF000000);

      buffer.vertex(mat, minX, maxY, minZ).color(0xFF000000);
      buffer.vertex(mat, minX, maxY, maxZ).color(0xFF000000);
      buffer.vertex(mat, minX, maxY, maxZ).color(0xFF000000);
      buffer.vertex(mat, maxX, maxY, maxZ).color(0xFF000000);
      buffer.vertex(mat, maxX, maxY, maxZ).color(0xFF000000);
      buffer.vertex(mat, maxX, maxY, minZ).color(0xFF000000);
      buffer.vertex(mat, maxX, maxY, minZ).color(0xFF000000);
      buffer.vertex(mat, minX, maxY, minZ).color(0xFF000000);

      buffer.vertex(mat, minX, minY, minZ).color(0xFF000000);
      buffer.vertex(mat, minX, maxY, minZ).color(0xFF000000);
      buffer.vertex(mat, minX, minY, maxZ).color(0xFF000000);
      buffer.vertex(mat, minX, maxY, maxZ).color(0xFF000000);
      buffer.vertex(mat, maxX, minY, maxZ).color(0xFF000000);
      buffer.vertex(mat, maxX, maxY, maxZ).color(0xFF000000);
      buffer.vertex(mat, maxX, minY, minZ).color(0xFF000000);
      buffer.vertex(mat, maxX, maxY, minZ).color(0xFF000000);
   }
}