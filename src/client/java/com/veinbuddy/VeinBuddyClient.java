package com.veinbuddy;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.Math;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Scanner;
import java.util.Spliterator;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.PickaxeItem;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Position;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class VeinBuddyClient implements ClientModInitializer {

  private final static MinecraftClient mc = MinecraftClient.getInstance();
  private final static double speed = 0.2f;
  private final static double radius = 0.5;
  private final static int digRange = 10;
  private final static double placeRange = 6.0;
  private final static double visibilityRange = 12.0;
  private final static int maxTicks = (int) (placeRange / speed);
  private final static int delay = 5;

  private int selectionTicks = 0;
  private Vec3d pos = null;
  private Vec3i posBlock = null;
  private int saveNumber = 0;
  private int changeNumber = 0;
  private boolean showOutlines;

  private Set<Vec3i> selections = new ConcurrentSkipListSet<Vec3i>();
  private Map<Vec3i, WallGroup> selectionWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

  private Set<Vec3i> boundary = new ConcurrentSkipListSet<Vec3i>();
  private Set<Vec3i> wallBlocks = new ConcurrentSkipListSet<Vec3i>();
  private Map<Vec3i, WallGroup> wallBlockWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

  @Override
  public void onInitializeClient() {
    ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onStart(client));
    ClientTickEvents.END_CLIENT_TICK.register(client -> onTick(client));
    ClientTickEvents.END_CLIENT_TICK.register(client -> saveSelections(client));
    WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> afterTranslucent(context));
    WorldRenderEvents.LAST.register(context -> wireframeOverlays(context));
    LiteralArgumentBuilder<FabricClientCommandSource> clearAll = ClientCommandManager.literal("clearAll");
    LiteralArgumentBuilder<FabricClientCommandSource> clearFar = ClientCommandManager.literal("clearFar");
    LiteralArgumentBuilder<FabricClientCommandSource> clearNear = ClientCommandManager.literal("clearNear");
    LiteralArgumentBuilder<FabricClientCommandSource> hideOutlines = ClientCommandManager.literal("hideOutlines");
    LiteralArgumentBuilder<FabricClientCommandSource> showOutlines = ClientCommandManager.literal("showOutlines");
    LiteralArgumentBuilder<FabricClientCommandSource> reload = ClientCommandManager.literal("reload");
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
      dispatcher.register(clearAll.executes(context -> onClearAll(context)));
      dispatcher.register(clearFar.executes(context -> onClearFar(context)));
      dispatcher.register(clearNear.executes(context -> onClearNear(context)));
      dispatcher.register(hideOutlines.executes(context -> onHideOutlines(context)));
      dispatcher.register(showOutlines.executes(context -> onShowOutlines(context)));
      dispatcher.register(reload.executes(context -> onReload(context)));
    });
  }

  private File getSaveFile(MinecraftClient client) {
    ServerInfo serverInfo = client.getCurrentServerEntry();
    if (null == serverInfo)
      return null;
    String address = serverInfo.address;
    return new File(client.runDirectory, address + ".txt");
  }

  private void saveSelections(MinecraftClient client) {
    if (!(changeNumber > saveNumber)) return;
    try {
      File saveFile = getSaveFile(client);
      if (null == saveFile)
        return;
      FileWriter fileWriter = new FileWriter(saveFile, false);
      for (Vec3i selection : selections) {
         fileWriter.write(selection.getX() + " " + selection.getY() + " " + selection.getZ() + "\n");
      }
      fileWriter.close();
      saveNumber = changeNumber;
    } catch (IOException e){
      System.out.println("Sad!");
    }
  }

  private void onStart(MinecraftClient client) {
    File saveFile = getSaveFile(client);
    if (null == saveFile)
      return;
    try {
      Scanner sc = new Scanner(saveFile);
      while (sc.hasNext()){
        int x = sc.nextInt();
        int y = sc.nextInt();
        int z = sc.nextInt();
        addSelection(new Vec3i(x, y, z), true);
        sc.nextLine();
      }
    } catch (IOException e) {
      System.out.println("Bad!");
    }
    updateWalls();
  }

  private int onClearAll(CommandContext<FabricClientCommandSource> ctx) {
    selections = new ConcurrentSkipListSet<Vec3i>();
    selectionWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

    boundary = new ConcurrentSkipListSet<Vec3i>();
    wallBlocks = new ConcurrentSkipListSet<Vec3i>();
    wallBlockWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

    changeNumber += 1;

    return 0;
  }

  private int onClearNear(CommandContext<FabricClientCommandSource> ctx) {
    onClear(true);
    return 0;
  }

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
    Vec3d pos = mc.player.getPos();
    posBlock = new Vec3i((int)Math.floor(pos.getX()), 
                         (int)Math.floor(pos.getY()), 
	                 (int)Math.floor(pos.getZ()));

    Map<Vec3i, Vec3i> selectionGroup = new HashMap<Vec3i, Vec3i>();
    Set<Vec3i> nearSelections = new HashSet<Vec3i>();

    for (Vec3i selection : selections) {
      selectionGroup.put(selection, selection);
    }
    for (Vec3i selection : selections) {
      for (int i = -2 * digRange; i <= 2 * digRange; ++i) {
        for (int j = -2 * digRange; j <= 2 * digRange; ++j) {
          for (int k = -2 * digRange; k <= 2 * digRange; ++k) {
            Vec3i block = selection.add(i, j, k);
	    if (posBlock.equals(block)) {
              nearSelections.add(selection);
	    }
	    if (selections.contains(block)) {
              Vec3i group = find(selectionGroup, selection);
	      Vec3i blockGroup = find(selectionGroup, block);
              selectionGroup.put(group, blockGroup);
	    }
	  }
	}
      }
    }
    Set<Vec3i> nearSelectionsGroups = new HashSet<Vec3i>();
    for (Vec3i selection : nearSelections) {
      nearSelectionsGroups.add(find(selectionGroup, selection));
    }
    for (Vec3i selection : selections) {
      if (clearNear) {
        if (nearSelectionsGroups.contains(find(selectionGroup, selection))) {
          removeSelection(selection, true);
        }
      } else {
        if (!nearSelectionsGroups.contains(find(selectionGroup, selection))) {
          removeSelection(selection, true);
        }
      }
    }
    changeNumber += 1;
    updateWalls();
  }

  private int onHideOutlines(CommandContext<FabricClientCommandSource> ctx) {
    showOutlines = false;
    return 0;
  }

  private int onShowOutlines(CommandContext<FabricClientCommandSource> ctx) {
    showOutlines = true;
    return 0;
  }

  private int onReload(CommandContext<FabricClientCommandSource> ctx) {
    selections = new ConcurrentSkipListSet<Vec3i>();
    selectionWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

    boundary = new ConcurrentSkipListSet<Vec3i>();
    wallBlocks = new ConcurrentSkipListSet<Vec3i>();
    wallBlockWalls = new ConcurrentHashMap<Vec3i, WallGroup>();
    File saveFile = getSaveFile(mc);

    if (null == saveFile)
      return -1;
    try {
      Scanner sc = new Scanner(saveFile);
      while (sc.hasNext()){
        int x = sc.nextInt();
        int y = sc.nextInt();
        int z = sc.nextInt();
        addSelection(new Vec3i(x, y, z), true);
        sc.nextLine();
      }
    } catch (IOException e) {
      System.out.println("Bad!");
    }
    updateWalls();

    return 0;
  }

  private void onTick(MinecraftClient client) {
    if (null == client.player) return;
    if (null == mc.mouse) return;
    if (null == mc.world) return;
    if (!(client.player.getInventory().getMainHandStack().getItem() instanceof PickaxeItem)) {
      pos = null;
      posBlock = null;
      selectionTicks = 0;
      return;
    }
    boolean rightClick = mc.mouse.wasRightButtonClicked();
    Vec3d playerPos = client.player.getPos().add(0.0f, 1.6f, 0.0f);
    Vec3d playerDir = client.player.getRotationVector();
    if (!rightClick && 0 != selectionTicks && 10 > selectionTicks) {
      removeLookedAtSelection(playerPos, playerDir);
    }
    if (!rightClick && 10 <= selectionTicks) {
      addSelection(posBlock, false);
    }
    if (!rightClick){
      pos = null;
      posBlock = null;
    }
    selectionTicks = rightClick ? selectionTicks + 1 : 0;
    selectionTicks = Math.min(selectionTicks, maxTicks + delay);
    if (10 > selectionTicks) return;
    pos = playerPos.add(playerDir.multiply(speed).multiply(selectionTicks - delay));
    posBlock = new Vec3i((int)Math.floor(pos.getX()), 
		         (int)Math.floor(pos.getY()), 
			 (int)Math.floor(pos.getZ()));
  }

  private boolean rayIntersectsSphere(Vec3d orig, Vec3d rot, Vec3d center) {
    Vec3d L = orig.subtract(center);
    double a = rot.dotProduct(rot);
    double b = 2.0 * rot.dotProduct(L);
    double c = L.dotProduct(L) - radius * radius;
    double discr = b*b - 4.0 * a * c;
    if (discr < 0)
      return false;
    double q = (b > 0.0) ? -.5 * (b + Math.sqrt(discr)) : -.5 * (b - Math.sqrt(discr));
    double t0 = q / a;
    double t1 = c / q;
    if (t0 < 0 && t1 < 0)
	return false;
    return true;
  }

  private boolean rayIntersectsXFace(Vec3d orig, Vec3d rot, Vec3i block){
    double minX = (double)block.getX();
    double minY = (double)block.getY();
    double minZ = (double)block.getZ();
    double maxX = minX + 1.0;
    double maxY = minY + 1.0;
    double maxZ = minZ + 1.0;
    if (0.0 == rot.getX()) return false;
    double nearX = (minX > orig.getX()) ? minX : maxX;
    double tX =  (nearX - orig.getX()) / rot.getX();
    Vec3d iPos = orig.add(rot.multiply(tX));
    return minY <= iPos.getY() && iPos.getY() <= maxY && minZ <= iPos.getZ() && iPos.getZ() <= maxZ;
  }

  private boolean rayIntersectsYFace(Vec3d orig, Vec3d rot, Vec3i block){
    double minX = (double)block.getX();
    double minY = (double)block.getY();
    double minZ = (double)block.getZ();
    double maxX = minX + 1.0;
    double maxY = minY + 1.0;
    double maxZ = minZ + 1.0;
    if (0.0 == rot.getY()) return false;
    double nearY = (minY > orig.getY()) ? minY : maxY;
    double tY =  (nearY - orig.getY()) / rot.getY();
    Vec3d iPos = orig.add(rot.multiply(tY));
    return minX <= iPos.getX() && iPos.getX() <= maxX && minZ <= iPos.getZ() && iPos.getZ() <= maxZ;
  }

  private boolean rayIntersectsZFace(Vec3d orig, Vec3d rot, Vec3i block){
    double minX = (double)block.getX();
    double minY = (double)block.getY();
    double minZ = (double)block.getZ();
    double maxX = minX + 1.0;
    double maxY = minY + 1.0;
    double maxZ = minZ + 1.0;
    if (0.0 == rot.getZ()) return false;
    double nearZ = (minZ > orig.getZ()) ? minZ : maxZ;
    double tZ = (nearZ - orig.getZ()) / rot.getZ();
    Vec3d iPos = orig.add(rot.multiply(tZ));
    return minX <= iPos.getX() && iPos.getX() <= maxX && minY <= iPos.getY() && iPos.getY() <= maxY;
  }

  private void addSelection(Vec3i selection, boolean bulk){
    if (selections.contains(selection)) return;
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
    selections.add(selection);
    Octree selectionTree = new Octree();
    selectionTree.add(selections.spliterator());

    Set<Vec3i> potentialWallBlocks = new HashSet<Vec3i>();
    int boundaryRange = digRange + 1;
    for (int x = -1 * boundaryRange; x <= boundaryRange; ++x){
      for (int y = -1 * boundaryRange; y <= boundaryRange; ++y){
        for (int z = -1 * boundaryRange; z <= boundaryRange; ++z){
          Vec3i block = selection.add(x, y, z);
          boolean xWall = Math.abs(x) == digRange;
          boolean yWall = Math.abs(y) == digRange;
          boolean zWall = Math.abs(z) == digRange;
          boolean xBound = Math.abs(x) == boundaryRange;
          boolean yBound = Math.abs(y) == boundaryRange;
          boolean zBound = Math.abs(z) == boundaryRange;
	  boolean bound = xBound || yBound || zBound;
          if ((xWall || yWall || zWall) && !bound)
            potentialWallBlocks.add(block);
          if (bound && !selectionTree.collides(block, digRange))
            boundary.add(block);
          else
            boundary.remove(block);
        }
      }
    }

    for (Vec3i block : potentialWallBlocks) {
      boolean pWest  = boundary.contains(block.add(-1,  0,  0));
      boolean pEast  = boundary.contains(block.add( 1,  0,  0));
      boolean pDown  = boundary.contains(block.add( 0, -1,  0));
      boolean pUp    = boundary.contains(block.add( 0,  1,  0));
      boolean pSouth = boundary.contains(block.add( 0,  0, -1));
      boolean pNorth = boundary.contains(block.add( 0,  0,  1));
      if (pWest || pEast || pDown || pUp || pSouth || pNorth)
        wallBlocks.add(block);
    }

    if (!bulk) {
      changeNumber += 1;
      updateWalls();
    }
  }

  private void removeLookedAtSelection(Vec3d pos, Vec3d dir){
    Iterator<Vec3i> sIter = selections.iterator();
    Vec3i nearest = null;
    double nearestRange = visibilityRange;
    while (sIter.hasNext()) {
      Vec3i selectedBlock = sIter.next();
      Vec3d center = Vec3d.ofCenter(selectedBlock);
      if (!rayIntersectsSphere(pos, dir, center))
        continue;
      if (pos.distanceTo(center) > nearestRange)
	continue;
      nearest = selectedBlock;
      nearestRange = pos.distanceTo(center);
    }
    if (null == nearest)
      return;
    removeSelection(nearest, false);
  }

  private void removeSelection(Vec3i selection, boolean bulk){
    selections.remove(selection);
    selectionWalls.remove(selection);

    Octree selectionTree = new Octree();
    selectionTree.add(selections.spliterator());
    Set<Vec3i> caught = new HashSet<Vec3i>();
    Set<Vec3i> orphaned = new HashSet<Vec3i>();
    int wallRange = digRange + 1;
    for (int x = -1 * wallRange; x <= wallRange; ++x){
      for (int y = -1 * wallRange; y <= wallRange; ++y){
        for (int z = -1 * wallRange; z <= wallRange; ++z){
          Vec3i block = selection.add(x, y, z);
          wallBlocks.remove(block);
          wallBlockWalls.remove(block);
          boundary.remove(block);
          if (selectionTree.collides(block, digRange))
            caught.add(block);
          else
            orphaned.add(block);
        }
      }
    }
    for (Vec3i block : orphaned) {
      Vec3i vWest  = block.add(-1,  0,  0);
      Vec3i vEast  = block.add( 1,  0,  0);
      Vec3i vDown  = block.add( 0, -1,  0);
      Vec3i vUp    = block.add( 0,  1,  0);
      Vec3i vSouth = block.add( 0,  0, -1);
      Vec3i vNorth = block.add( 0,  0,  1);
      boolean pWest   = wallBlocks.contains(vWest)  || caught.contains(vWest);
      boolean pEast   = wallBlocks.contains(vEast)  || caught.contains(vEast);
      boolean pDown   = wallBlocks.contains(vDown)  || caught.contains(vDown);
      boolean pUp     = wallBlocks.contains(vUp)    || caught.contains(vUp);
      boolean pSouth  = wallBlocks.contains(vSouth) || caught.contains(vSouth);
      boolean pNorth  = wallBlocks.contains(vNorth) || caught.contains(vNorth);
      if (pWest || pEast || pDown || pUp || pSouth || pNorth)
        boundary.add(block);
    }
    for (Vec3i block : caught) {
      boolean pWest  = boundary.contains(block.add(-1,  0,  0));
      boolean pEast  = boundary.contains(block.add( 1,  0,  0));
      boolean pDown  = boundary.contains(block.add( 0, -1,  0));
      boolean pUp    = boundary.contains(block.add( 0,  1,  0));
      boolean pSouth = boundary.contains(block.add( 0,  0, -1));
      boolean pNorth = boundary.contains(block.add( 0,  0,  1));
      if (pWest || pEast || pDown || pUp || pSouth || pNorth)
        wallBlocks.add(block);
    }
    if (!bulk) {
      changeNumber += 1;
      updateWalls();
    }
  }

  private void updateWalls() {
    for (Vec3i block : wallBlocks) {
      WallGroup group = updateWall(block);
      wallBlockWalls.put(block, group);
    }
  }

  private WallGroup updateWall(Vec3i block) {
    boolean pWest  = boundary.contains(block.add(-1,  0,  0));
    boolean pEast  = boundary.contains(block.add( 1,  0,  0));
    boolean pDown  = boundary.contains(block.add( 0, -1,  0));
    boolean pUp    = boundary.contains(block.add( 0,  1,  0));
    boolean pSouth = boundary.contains(block.add( 0,  0, -1));
    boolean pNorth = boundary.contains(block.add( 0,  0,  1));

    if ((pWest && pEast) || (pDown && pUp) || (pSouth && pNorth))
      System.out.println("Error!");

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

    float xVal = minX+adjustment;
    float yVal = minY+adjustment;
    float zVal = minZ+adjustment;
    int xBlock = -1;
    int yBlock = -1;
    int zBlock = -1;

    if (pEast) {
      xVal = maxX-adjustment;
      xBlock = 1;
    }
    if (pUp) {
      yVal = maxY-adjustment;
      yBlock = 1;
    }
    if (pNorth) {
      zVal = maxZ-adjustment;
      zBlock = 1;
    }

    if (pWest || pEast) {
      boolean upNorth     = !boundary.contains(block.add(xBlock, 1, 1));
      boolean up          = !boundary.contains(block.add(xBlock, 1, 0));
      boolean upSouth     = !boundary.contains(block.add(xBlock, 1, -1));

      boolean north       = !boundary.contains(block.add(xBlock, 0, 1));
      boolean neutral     = !boundary.contains(block.add(xBlock, 0, 0));
      boolean south       = !boundary.contains(block.add(xBlock, 0, -1));

      boolean downNorth   = !boundary.contains(block.add(xBlock, -1, 1));
      boolean down        = !boundary.contains(block.add(xBlock, -1, 0));
      boolean downSouth   = !boundary.contains(block.add(xBlock, -1, -1));

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
      boolean north     = !boundary.contains(block.add(0, yBlock, 1));
      boolean northWest = !boundary.contains(block.add(-1, yBlock, 1));

      boolean east      = !boundary.contains(block.add(1, yBlock, 0));
      boolean neutral   = !boundary.contains(block.add(0, yBlock, 0));
      boolean west      = !boundary.contains(block.add(-1, yBlock, 0));

      boolean southEast = !boundary.contains(block.add(1, yBlock, -1));
      boolean south     = !boundary.contains(block.add(0, yBlock, -1));
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
      boolean upEast    = !boundary.contains(block.add(1, 1, zBlock));
      boolean up        = !boundary.contains(block.add(0, 1, zBlock));
      boolean upWest    = !boundary.contains(block.add(-1, 1, zBlock));

      boolean east      = !boundary.contains(block.add(1, 0, zBlock));
      boolean neutral   = !boundary.contains(block.add(0, 0, zBlock));
      boolean west      = !boundary.contains(block.add(-1, 0, zBlock));

      boolean downEast  = !boundary.contains(block.add(1, -1, zBlock));
      boolean down      = !boundary.contains(block.add(0, -1, zBlock));
      boolean downWest  = !boundary.contains(block.add(-1, -1, zBlock));

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
    if (null == mc.player) return;
    if (null == posBlock && (!showOutlines || selections.isEmpty())) return;
    
    boolean render = false;

    Vec3d camPos = ctx.camera().getPos();
    MatrixStack stack = ctx.matrixStack();
    stack.push();
    stack.translate(-camPos.getX(), -camPos.getY(), -camPos.getZ());
    Matrix4f mat = stack.peek().getPositionMatrix();
    Tessellator tessellator = Tessellator.getInstance();
    BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

    if (null != posBlock){
      render = true;
      buildVerticesOutline(buffer, mat, posBlock);
    }

    if (showOutlines && !selections.isEmpty()) {
      render = true;
      for(Vec3i selection : selections) {
	 buildVerticesOutline(buffer, mat, selection);
      }
    }

    if (render) {
      RenderSystem.setShader(GameRenderer::getPositionColorProgram);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

      BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    stack.pop();
  }

  private void afterTranslucent(WorldRenderContext ctx) {
    if (selections.isEmpty()) return;

    Vec3d camPos = ctx.camera().getPos();
    Frustum frustum = ctx.frustum();
    Vector3f camVec = new Vector3f((float)-camPos.getX(), (float)-camPos.getY(), (float)-camPos.getZ());
    Matrix4f mat = new Matrix4f();
    mat.translation(camVec);

    RenderSystem.enableBlend();
    RenderSystem.disableCull();

    Tessellator tessellator = Tessellator.getInstance();
    BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

    int color = 0x60008000;
    boolean render = false;
    
    for (Vec3i selection : selections) {
      Box approximateBorders = new Box(new BlockPos(selection));
      if (frustum.isVisible(approximateBorders)) {
        render = true;
        WallGroup group = selectionWalls.get(selection);
        buildVerticesWall(buffer, mat, group.getWestWall(), color);
        buildVerticesWall(buffer, mat, group.getEastWall(), color);
        buildVerticesWall(buffer, mat, group.getDownWall(), color);
        buildVerticesWall(buffer, mat, group.getUpWall(), color);
        buildVerticesWall(buffer, mat, group.getSouthWall(), color);
        buildVerticesWall(buffer, mat, group.getNorthWall(), color);
      }
    }

    if (render) {
      RenderSystem.setShader(GameRenderer::getPositionColorProgram);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    BufferBuilder tbuffer = tessellator.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
    
    color = 0x60800000;
    render = false;

    for (Vec3i wallBlock : wallBlocks) {
      Box approximateBorders = new Box(new BlockPos(wallBlock));
      if (frustum.isVisible(approximateBorders)) {
        render = true;
        buildVerticesWallBlock(tbuffer, mat, wallBlock, color);
      }
    }

    if (render) {
      RenderSystem.setShader(GameRenderer::getPositionColorProgram);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      BufferRenderer.drawWithGlobalProgram(tbuffer.end());
    }

    GL11.glEnable(GL11.GL_LINE_SMOOTH);
    BufferBuilder lbuffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

    color = 0xFF000000;
    render = false;
    for (Vec3i wallBlock : wallBlocks) {
      Box approximateBorders = new Box(new BlockPos(wallBlock));
      if (frustum.isVisible(approximateBorders)) {
        render = true;
        buildVerticesWallBlockOutline(lbuffer, mat, wallBlock, color);
      }
    }

    if (render) {
      RenderSystem.setShader(GameRenderer::getPositionColorProgram);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      BufferRenderer.drawWithGlobalProgram(lbuffer.end());
    }

    RenderSystem.disableBlend();
    RenderSystem.enableCull();
  }

  private void buildVerticesWallBlock(BufferBuilder buffer, Matrix4f mat, Vec3i block, int color) {
    WallGroup group = wallBlockWalls.get(block);
    WallVertexGroup west, east, down, up, south, north;
    west  = group.getWestWall();
    east  = group.getEastWall();
    down  = group.getDownWall();
    up    = group.getUpWall();
    south = group.getSouthWall();
    north = group.getNorthWall();

    if (null != west)
      buildVerticesWall(buffer, mat, west, color);
    if (null != east)
      buildVerticesWall(buffer, mat, east, color);
    if (null != down)
      buildVerticesWall(buffer, mat, down, color);
    if (null != up)
      buildVerticesWall(buffer, mat, up, color);
    if (null != south)
      buildVerticesWall(buffer, mat, south, color);
    if (null != north)
      buildVerticesWall(buffer, mat, north, color);
  }

  private void buildVerticesWall(BufferBuilder buffer, Matrix4f mat, WallVertexGroup g, int color) {
    buffer.vertex(mat, g.v00.x, g.v00.y, g.v00.z).color(color);
    buffer.vertex(mat, g.v01.x, g.v01.y, g.v01.z).color(color);
    buffer.vertex(mat, g.v11.x, g.v11.y, g.v11.z).color(color);

    buffer.vertex(mat, g.v00.x, g.v00.y, g.v00.z).color(color);
    buffer.vertex(mat, g.v10.x, g.v10.y, g.v10.z).color(color);
    buffer.vertex(mat, g.v11.x, g.v11.y, g.v11.z).color(color);
  }

  private void buildVerticesWallBlockOutline(BufferBuilder buffer, Matrix4f mat, Vec3i block, int color) {
    WallGroup group = wallBlockWalls.get(block);
    WallVertexGroup west, east, down, up, south, north;

    west  = group.getWestWall();
    east  = group.getEastWall();
    down  = group.getDownWall();
    up    = group.getUpWall();
    south = group.getSouthWall();
    north = group.getNorthWall();

    if (null != west)
      buildVerticesWallOutline(buffer, mat, west, color);
    if (null != east)
      buildVerticesWallOutline(buffer, mat, east, color);
    if (null != down)
      buildVerticesWallOutline(buffer, mat, down, color);
    if (null != up)
      buildVerticesWallOutline(buffer, mat, up, color);
    if (null != south)
      buildVerticesWallOutline(buffer, mat, south, color);
    if (null != north)
      buildVerticesWallOutline(buffer, mat, north, color);
  }

  private void buildVerticesWallOutline(BufferBuilder buffer, Matrix4f mat, WallVertexGroup g, int color) {
    buffer.vertex(mat, g.v00.x, g.v00.y, g.v00.z).color(color);
    buffer.vertex(mat, g.v01.x, g.v01.y, g.v01.z).color(color);
    buffer.vertex(mat, g.v01.x, g.v01.y, g.v01.z).color(color);
    buffer.vertex(mat, g.v11.x, g.v11.y, g.v11.z).color(color);
    buffer.vertex(mat, g.v11.x, g.v11.y, g.v11.z).color(color);
    buffer.vertex(mat, g.v10.x, g.v10.y, g.v10.z).color(color);
    buffer.vertex(mat, g.v10.x, g.v10.y, g.v10.z).color(color);
    buffer.vertex(mat, g.v00.x, g.v00.y, g.v00.z).color(color);
  }

  private void buildVerticesOutline(BufferBuilder buffer, Matrix4f mat, Vec3i block){
    float minX = (float)block.getX();
    float minY = (float)block.getY();
    float minZ = (float)block.getZ();

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

  private class WallVertexGroup {
    public Vector3f v00, v01, v10, v11;

    public WallVertexGroup() {
    }

    public void updateGroup(Vector3f v00, Vector3f v01, Vector3f v10, Vector3f v11) {
      this.v00 = v00;
      this.v01 = v01;
      this.v10 = v10;
      this.v11 = v11;
    }
  }

  private class WallGroup {
    protected Vector3f v000, v001, v010, v011, v100, v101, v110, v111;
    protected WallVertexGroup west, east, down, up, south, north;

    public WallGroup() {
      this(false, false, false, false, false, false);
    }

    public WallGroup(boolean pWest, boolean pEast, boolean pDown, boolean pUp, boolean pSouth, boolean pNorth) {
      west  = pWest  ? new WallVertexGroup() : null;
      east  = pEast  ? new WallVertexGroup() : null;
      down  = pDown  ? new WallVertexGroup() : null;
      up    = pUp    ? new WallVertexGroup() : null;
      south = pSouth ? new WallVertexGroup() : null;
      north = pNorth ? new WallVertexGroup() : null;
      v000 = null;
      v001 = null;
      v010 = null;
      v011 = null;
      v100 = null;
      v101 = null;
      v110 = null;
      v111 = null;
    }

    public WallVertexGroup getWestWall() {
      if (null != west)
        west.updateGroup(v000, v001, v010, v011);
      return west;
    }

    public WallVertexGroup getEastWall() {
      if (null != east)
        east.updateGroup(v100, v101, v110, v111);
      return east;
    }

    public WallVertexGroup getDownWall() {
      if (null != down)
        down.updateGroup(v000, v001, v100, v101);
      return down;
    }

    public WallVertexGroup getUpWall() {
      if (null != up)
        up.updateGroup(v010, v011, v110, v111);
      return up;
    }

    public WallVertexGroup getSouthWall() {
      if (null != south)
        south.updateGroup(v000, v010, v100, v110);
      return south;
    }

    public WallVertexGroup getNorthWall() {
      if (null != north)
        north.updateGroup(v001, v011, v101, v111);
      return north;
    }

    public void putWestWall() {
      if (null == west)
        west = new WallVertexGroup();
    }

    public void putEastWall() {
      if (null == east)
        east = new WallVertexGroup();
    }

    public void putDownWall() {
      if (null == down)
        down = new WallVertexGroup();
    }

    public void putUpWall() {
      if (null == up)
        up = new WallVertexGroup();
    }

    public void putSouthWall() {
      if (null == south)
        south = new WallVertexGroup();
    }

    public void putNorthWall() {
      if (null == north)
        north = new WallVertexGroup();
    }

    public void putVertex(Vector3f vec, boolean east, boolean up, boolean north) {
      boolean west = !east;
      boolean down = !up;
      boolean south = !north;
      if (west && down && south)
	v000 = vec;
      if (west && down && north)
	v001 = vec;
      if (west && up && south)
	v010 = vec;
      if (west && up && north)
	v011 = vec;
      if (east && down && south)
	v100 = vec;
      if (east && down && north)
	v101 = vec;
      if (east && up && south)
	v110 = vec;
      if (east && up && north)
	v111 = vec;
    }
  }

  private class Octree {
    protected Octree tree000, tree001, tree010, tree011, tree100, tree101, tree110, tree111;
    protected Vec3i block;
    protected boolean removed;
    public Octree() {
      tree000 = null;
      tree001 = null;
      tree010 = null;
      tree011 = null;
      tree100 = null;
      tree101 = null;
      tree110 = null;
      tree111 = null;
      block = null;
    }

    public void add(Spliterator<Vec3i> right) {
      Spliterator<Vec3i> left = right.trySplit();
      if (null == left) {
	right.forEachRemaining((block) -> add(block));
      } else {
	 right.tryAdvance((block) -> add(block));
	 add(left);
	 add(right);
      }
    }

    public void add(Vec3i newBlock) {
      if (null == newBlock) return;
      if (newBlock.equals(block)) return;

      if (null == block) {
	 block = newBlock;
         tree000 = new Octree();
         tree001 = new Octree();
         tree010 = new Octree();
         tree011 = new Octree();
         tree100 = new Octree();
         tree101 = new Octree();
         tree110 = new Octree();
         tree111 = new Octree();
	 return;
      }

      boolean east  = block.getX() <= newBlock.getX();
      boolean up    = block.getY() <= newBlock.getY();
      boolean north = block.getZ() <= newBlock.getZ();
      boolean west  = !east;
      boolean down  = !up;
      boolean south = !north;

      if (west && down && south)
	 tree000.add(newBlock);
      if (west && down && north)
	 tree001.add(newBlock);
      if (west && up && south)
	 tree010.add(newBlock);
      if (west && up && north)
	 tree011.add(newBlock);
      if (east && down && south)
	 tree100.add(newBlock);
      if (east && down && north)
	 tree101.add(newBlock);
      if (east && up && south)
	 tree110.add(newBlock);
      if (east && up && north)
	 tree111.add(newBlock);
    }

    public boolean collides(Vec3i collider, int manhattanRadius) {
      if (null == block)
	 return false;
      int x = collider.getX();
      int y = collider.getY();
      int z = collider.getZ();
      int minX = x - manhattanRadius;
      int minY = y - manhattanRadius;
      int minZ = z - manhattanRadius;
      int maxX = x + manhattanRadius;
      int maxY = y + manhattanRadius;
      int maxZ = z + manhattanRadius;

      boolean west  = maxX < block.getX();
      boolean east  = minX > block.getX();
      boolean down  = maxY < block.getY();
      boolean up    = minY > block.getY();
      boolean south = maxZ < block.getZ();
      boolean north = minZ > block.getZ();
      
      boolean xHit = !west && !east;
      boolean yHit = !down && !up;
      boolean zHit = !south && !north;

      boolean collision = !removed && xHit && yHit && zHit;

      boolean p000 = !east && !up   && !north;
      boolean p001 = !east && !up   && !south;
      boolean p010 = !east && !down && !north;
      boolean p011 = !east && !down && !south;
      boolean p100 = !west && !up   && !north;
      boolean p101 = !west && !up   && !south;
      boolean p110 = !west && !down && !north;
      boolean p111 = !west && !down && !south;

      return collision || 
        (p000 && tree000.collides(collider, manhattanRadius)) ||
        (p001 && tree001.collides(collider, manhattanRadius)) ||
        (p010 && tree010.collides(collider, manhattanRadius)) ||
        (p011 && tree011.collides(collider, manhattanRadius)) ||
        (p100 && tree100.collides(collider, manhattanRadius)) ||
        (p101 && tree101.collides(collider, manhattanRadius)) ||
        (p110 && tree110.collides(collider, manhattanRadius)) ||
        (p111 && tree111.collides(collider, manhattanRadius));
      }
    }
  }
