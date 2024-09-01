package com.veinbuddy;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.systems.RenderSystem;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.Math;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Scanner;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.PickaxeItem;
import net.minecraft.util.math.Position;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import net.fabricmc.api.ClientModInitializer;
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
  private final static int digRange = 7;
  private final static double placeRange = 6.0;
  private final static double visibilityRange = 12.0;
  private final static int maxTicks = (int) (placeRange / speed);
  private final static int delay = 5;
  private final static Float fadeTicks = 60.0f;

  private int selectionTicks = 0;
  private Vec3d pos = null;
  private Vec3i posBlock = null;
  private int saveNumber = 0;
  private int changeNumber = 0;

  private Queue<Vec3i> fadingSelections = new ConcurrentLinkedQueue();
  private Map<Vec3i, Float> faders = new ConcurrentHashMap<Vec3i, Float>();

  private Set<Vec3i> selections = new ConcurrentSkipListSet<Vec3i>();
  private Map<Vec3i, WallGroup> selectionWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

  private Set<Vec3i> digSet = new ConcurrentSkipListSet<Vec3i>();
  private Set<Vec3i> wallBlocks = new ConcurrentSkipListSet<Vec3i>();
  private Map<Vec3i, WallGroup> wallBlockWalls = new ConcurrentHashMap<Vec3i, WallGroup>();

  @Override
  public void onInitializeClient() {
    ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onStart(client));
    ClientTickEvents.END_CLIENT_TICK.register(client -> onTick(client));
    ClientTickEvents.END_CLIENT_TICK.register(client -> saveSelections(client));
    WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> afterTranslucent(context));
    WorldRenderEvents.LAST.register(context -> wireframeOverlays(context));
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
      removeSelections(playerPos, playerDir);
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

  private void addSelection(Vec3i selection, boolean loading){
    if (!selections.contains(selection)) {
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
      for (int x = -1 * digRange; x <= digRange; ++x){
        for (int y = -1 * digRange; y <= digRange; ++y){
          for (int z = -1 * digRange; z <= digRange; ++z){
            Vec3i block = selection.add(x, y, z);
            boolean xBound = Math.abs(x) == digRange;
            boolean yBound = Math.abs(y) == digRange;
            boolean zBound = Math.abs(z) == digRange;
            digSet.add(block);
            if (xBound || yBound || zBound)
	      wallBlocks.add(block);
	  }
        }
      }
    }
    if (!loading) {
      changeNumber += 1;
      fadingSelections.add(selection);
      faders.put(selection, fadeTicks);
      updateWalls();
    }
  }

  private void removeSelections(Vec3d pos, Vec3d dir){
    Set<Vec3i> orphanedBlocks = new HashSet<Vec3i>();
    Set<Vec3i> newWallBlocks = new HashSet<Vec3i>();
    Iterator<Vec3i> sIter = selections.iterator();
    while (sIter.hasNext()) {
      Vec3i selectedBlock = sIter.next();
      Vec3d center = Vec3d.ofCenter(selectedBlock);
      if (pos.distanceTo(center) > visibilityRange)
	continue;
      if (!rayIntersectsSphere(pos, dir, center))
        continue;
      sIter.remove();
      selectionWalls.remove(selectedBlock);
      if (fadingSelections.contains(selectedBlock)) {
        fadingSelections.remove(selectedBlock);
	faders.remove(selectedBlock);
      }

      changeNumber += 1;
      int wallRange = digRange + 1;
      for (int x = -1 * wallRange; x <= wallRange; ++x){
        for (int y = -1 * wallRange; y <= wallRange; ++y){
          for (int z = -1 * wallRange; z <= wallRange; ++z){
            boolean xBound = Math.abs(x) <= digRange;
	    boolean yBound = Math.abs(y) <= digRange;
	    boolean zBound = Math.abs(z) <= digRange;
	    Vec3i block = selectedBlock.add(x, y, z);
	    if (xBound && yBound && zBound)
              orphanedBlocks.add(block);
	    if (digSet.contains(block))
              newWallBlocks.add(block);
	  }
	}
      }
    }
    Iterator<Vec3i> orphanIterator = orphanedBlocks.iterator();
    while(orphanIterator.hasNext()){
      Vec3i orphan = orphanIterator.next();
      boolean nextOrphan = false;
      for (int x = -1 * digRange; x <= digRange; ++x) {
        for (int y = -1 * digRange; y <= digRange; ++y) {
          for (int z = -1 * digRange; z <= digRange; ++z) {
            if (selections.contains(orphan.add(x, y, z))) { 
	      nextOrphan = true;
	      orphanIterator.remove();
	      break;
	    }
	  }
	  if (nextOrphan)
	    break;
        }
	if (nextOrphan)
	  break;
      }
    }
    for (Vec3i orphan : orphanedBlocks) {
      digSet.remove(orphan); 
      if (newWallBlocks.contains(orphan))
        newWallBlocks.remove(orphan);
    }
    for (Vec3i block : newWallBlocks) {
      wallBlocks.add(block);
    }
    updateWalls();
  }

  private void updateWalls() {
    Iterator<Vec3i> iter = wallBlocks.iterator();
    while (iter.hasNext()) {
      Vec3i block = iter.next();

      if (!digSet.contains(block)){
	iter.remove();
	wallBlockWalls.remove(block);
	continue;
      }

      WallGroup group = updateWall(block);

      if (null == group.getWestWall() && null == group.getEastWall() &&
	  null == group.getDownWall() && null == group.getUpWall() &&
	  null == group.getSouthWall() && null == group.getNorthWall()) {
	iter.remove();
	wallBlockWalls.remove(block);
	continue;
      }

      wallBlockWalls.put(block, group);
    }
  }

  private WallGroup updateWall(Vec3i block) {
    boolean pWest  = !digSet.contains(block.add(-1,  0,  0));
    boolean pEast  = !digSet.contains(block.add( 1,  0,  0));
    boolean pDown  = !digSet.contains(block.add( 0, -1,  0));
    boolean pUp    = !digSet.contains(block.add( 0,  1,  0));
    boolean pSouth = !digSet.contains(block.add( 0,  0, -1));
    boolean pNorth = !digSet.contains(block.add( 0,  0,  1));

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
      boolean upNorth     = digSet.contains(block.add(xBlock, 1, 1));
      boolean up          = digSet.contains(block.add(xBlock, 1, 0));
      boolean upSouth     = digSet.contains(block.add(xBlock, 1, -1));

      boolean north       = digSet.contains(block.add(xBlock, 0, 1));
      boolean neutral     = digSet.contains(block.add(xBlock, 0, 0));
      boolean south       = digSet.contains(block.add(xBlock, 0, -1));

      boolean downNorth   = digSet.contains(block.add(xBlock, -1, 1));
      boolean down        = digSet.contains(block.add(xBlock, -1, 0));
      boolean downSouth   = digSet.contains(block.add(xBlock, -1, -1));

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
      boolean northEast = digSet.contains(block.add(1, yBlock, 1));
      boolean north     = digSet.contains(block.add(0, yBlock, 1));
      boolean northWest = digSet.contains(block.add(-1, yBlock, 1));

      boolean east      = digSet.contains(block.add(1, yBlock, 0));
      boolean neutral   = digSet.contains(block.add(0, yBlock, 0));
      boolean west      = digSet.contains(block.add(-1, yBlock, 0));

      boolean southEast = digSet.contains(block.add(1, yBlock, -1));
      boolean south     = digSet.contains(block.add(0, yBlock, -1));
      boolean southWest = digSet.contains(block.add(-1, yBlock, -1));

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
      boolean upEast    = digSet.contains(block.add(1, 1, zBlock));
      boolean up        = digSet.contains(block.add(0, 1, zBlock));
      boolean upWest    = digSet.contains(block.add(-1, 1, zBlock));

      boolean east      = digSet.contains(block.add(1, 0, zBlock));
      boolean neutral   = digSet.contains(block.add(0, 0, zBlock));
      boolean west      = digSet.contains(block.add(-1, 0, zBlock));

      boolean downEast  = digSet.contains(block.add(1, -1, zBlock));
      boolean down      = digSet.contains(block.add(0, -1, zBlock));
      boolean downWest  = digSet.contains(block.add(-1, -1, zBlock));

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

    // haven't thought about this thoroughly, might be buggy
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

    return wallGroup;
  }

  private void wireframeOverlays(WorldRenderContext ctx) {
    if (null == mc.player) return;
    if (null == posBlock && fadingSelections.isEmpty()) return;
    
    boolean render = false;

    Vec3d camPos = ctx.camera().getPos();
    MatrixStack stack = ctx.matrixStack();
    stack.push();
    stack.translate(-camPos.getX(), -camPos.getY(), -camPos.getZ());
    Matrix4f mat = stack.peek().getPositionMatrix();
    Tessellator tessellator = Tessellator.getInstance();
    BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

    if (null != posBlock){
      buildVerticesOutline(buffer, mat, posBlock);
      render = true;
    }

    float tickDelta = ctx.tickCounter().getTickDelta(false);
    if (!fadingSelections.isEmpty()) {
      Iterator<Vec3i> iter = fadingSelections.iterator();
      while(iter.hasNext()) {
	Vec3i block = iter.next();
	float timeLeft = faders.get(block);
	timeLeft -= tickDelta;
	if (timeLeft <= 0.0){
          iter.remove();
	  faders.remove(block);
	  continue;
	}
	faders.put(block, timeLeft);
        buildVerticesOutline(buffer, mat, block);
	render = true;
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
    Vector3f camVec = new Vector3f((float)-camPos.getX(), (float)-camPos.getY(), (float)-camPos.getZ());
    Matrix4f mat = new Matrix4f();
    mat.translation(camVec);
    RenderSystem.enableBlend();
    RenderSystem.disableCull();

    Tessellator tessellator = Tessellator.getInstance();
    BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

    int color = 0x60008000;
    boolean render = false;
    
    for (Vec3i block : selections) {
      render = true;
      WallGroup group = selectionWalls.get(block);
      buildVerticesWall(buffer, mat, group.getWestWall(), color);
      buildVerticesWall(buffer, mat, group.getEastWall(), color);
      buildVerticesWall(buffer, mat, group.getDownWall(), color);
      buildVerticesWall(buffer, mat, group.getUpWall(), color);
      buildVerticesWall(buffer, mat, group.getSouthWall(), color);
      buildVerticesWall(buffer, mat, group.getNorthWall(), color);
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
      render = true;
      buildVerticesWallBlock(tbuffer, mat, wallBlock, color);
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
      render = true;
      buildVerticesWallBlockOutline(lbuffer, mat, wallBlock, color);
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
}
