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

  private Set<Wall> wallSet = new ConcurrentSkipListSet<Wall>();
  
  private Set<Wall> insideCorner0 = new ConcurrentSkipListSet<Wall>();
  private Set<Wall> insideCorner1 = new ConcurrentSkipListSet<Wall>();
  private Set<Wall> insideCorner2 = new ConcurrentSkipListSet<Wall>();
  private Set<Wall> insideCorner3 = new ConcurrentSkipListSet<Wall>();

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
      selections.add(selection);
      selectionWalls.put(selection, new WallGroup(selection));
      Set<Vec3i> newWallBlocks = new HashSet<Vec3i>();
      for (int x = -1 * digRange; x <= digRange; ++x){
        for (int y = -1 * digRange; y <= digRange; ++y){
          for (int z = -1 * digRange; z <= digRange; ++z){
            Vec3i block = selection.add(x, y, z);
            boolean xBound = Math.abs(x) == digRange;
            boolean yBound = Math.abs(y) == digRange;
            boolean zBound = Math.abs(z) == digRange;
            digSet.add(block);
            if (xBound || yBound || zBound)
	      newWallBlocks.add(block);
	  }
        }
      }
      for (Vec3i block : newWallBlocks){
	wallBlocks.add(block);
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
      if (pos.distanceTo(center) > visibilityRange){
	continue;
      }
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
    while (iter.hasNext()){
      Vec3i block = iter.next();
      Wall upWall =    new Wall(block, WallType.UP);
      Wall downWall =  new Wall(block, WallType.DOWN);
      Wall northWall = new Wall(block, WallType.NORTH);
      Wall eastWall =  new Wall(block, WallType.EAST);
      Wall southWall = new Wall(block, WallType.SOUTH);
      Wall westWall =  new Wall(block, WallType.WEST);
      boolean up =    wallSet.contains(upWall);
      boolean down =  wallSet.contains(downWall);
      boolean north = wallSet.contains(northWall);
      boolean east =  wallSet.contains(eastWall);
      boolean south = wallSet.contains(southWall);
      boolean west =  wallSet.contains(westWall);

      if (!digSet.contains(block)){
	iter.remove();
        wallSet.remove(upWall);
        wallSet.remove(downWall);
        wallSet.remove(northWall);
        wallSet.remove(eastWall);
        wallSet.remove(southWall);
        wallSet.remove(westWall);
	continue;
      }

      west =  !digSet.contains(block.add(-1,  0,  0));
      east =  !digSet.contains(block.add( 1,  0,  0));
      down =  !digSet.contains(block.add( 0, -1,  0));
      up =    !digSet.contains(block.add( 0,  1,  0));
      south = !digSet.contains(block.add( 0,  0, -1));
      north = !digSet.contains(block.add( 0,  0,  1));

      if (up){
        wallSet.add(upWall);
	if (digSet.contains(block.add(-1, 1,  0)))
          insideCorner0.add(upWall);
	if (digSet.contains(block.add( 0, 1,  1)))
          insideCorner1.add(upWall);
	if (digSet.contains(block.add( 1, 1,  0)))
          insideCorner2.add(upWall);
	if (digSet.contains(block.add( 0, 1, -1)))
          insideCorner3.add(upWall);
      }
      else
        removeWall(upWall);
      if (down) {
        wallSet.add(downWall);
	if (digSet.contains(block.add(-1, -1,  0)))
          insideCorner0.add(downWall);
	if (digSet.contains(block.add( 0, -1,  1)))
          insideCorner1.add(downWall);
	if (digSet.contains(block.add( 1,  1,  0)))
          insideCorner2.add(downWall);
	if (digSet.contains(block.add( 0,  1, -1)))
          insideCorner3.add(downWall);
      }
      else
	removeWall(downWall);
      if (north) {
        wallSet.add(northWall);
	if (digSet.contains(block.add(-1,  0,  1)))
          insideCorner0.add(northWall);
	if (digSet.contains(block.add( 0,  1,  1)))
          insideCorner1.add(northWall);
	if (digSet.contains(block.add( 1,  0,  1)))
          insideCorner2.add(northWall);
	if (digSet.contains(block.add( 0, -1,  1)))
          insideCorner3.add(northWall);
      }
      else
	removeWall(northWall);
      if (east) {
        wallSet.add(eastWall);
	if (digSet.contains(block.add( 1, -1,  0)))
          insideCorner0.add(eastWall);
	if (digSet.contains(block.add( 1,  0,  1)))
          insideCorner1.add(eastWall);
	if (digSet.contains(block.add( 1,  1,  0)))
          insideCorner2.add(eastWall);
	if (digSet.contains(block.add( 1,  0, -1)))
          insideCorner3.add(eastWall);
      }
      else
	removeWall(eastWall);
      if (south) {
        wallSet.add(southWall);
	if (digSet.contains(block.add(-1,  0, -1)))
          insideCorner0.add(southWall);
	if (digSet.contains(block.add( 0,  1, -1)))
          insideCorner1.add(southWall);
	if (digSet.contains(block.add( 1,  0, -1)))
          insideCorner2.add(southWall);
	if (digSet.contains(block.add( 0, -1, -1)))
          insideCorner3.add(southWall);
      }
      else
	removeWall(southWall);
      if (west) {
        wallSet.add(westWall);
	if (digSet.contains(block.add(-1, -1,  0)))
          insideCorner0.add(westWall);
	if (digSet.contains(block.add(-1,  0,  1)))
          insideCorner1.add(westWall);
	if (digSet.contains(block.add(-1,  1,  0)))
          insideCorner2.add(westWall);
	if (digSet.contains(block.add(-1,  0, -1)))
          insideCorner3.add(westWall);
      }
      else
	removeWall(westWall);

      if (!(up || down || north || east || south || west))
	iter.remove();
    }
  }

  private void removeWall(Wall wall) {
     wallSet.remove(wall);
     insideCorner0.remove(wall);
     insideCorner1.remove(wall);
     insideCorner2.remove(wall);
     insideCorner3.remove(wall);
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
      buildVerticesWall(buffer, mat, group.up, color);
      buildVerticesWall(buffer, mat, group.down, color);
      buildVerticesWall(buffer, mat, group.north, color);
      buildVerticesWall(buffer, mat, group.east, color);
      buildVerticesWall(buffer, mat, group.south, color);
      buildVerticesWall(buffer, mat, group.west, color);
    }

    if (render) {
      RenderSystem.setShader(GameRenderer::getPositionColorProgram);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    BufferBuilder tbuffer = tessellator.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
    
    color = 0x60800000;
    render = false;
    for (Wall wall : wallSet) {
      render = true;
      buildVerticesWall(tbuffer, mat, wall, color);
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
    for (Wall wall : wallSet) 
    {
      render = true;
      Vector3f v0 = wall.getVertex0();
      Vector3f v1 = wall.getVertex1();
      Vector3f v2 = wall.getVertex2();
      Vector3f v3 = wall.getVertex3();
      lbuffer.vertex(mat, v0.x, v0.y, v0.z).color(color);
      lbuffer.vertex(mat, v1.x, v1.y, v1.z).color(color);
      lbuffer.vertex(mat, v1.x, v1.y, v1.z).color(color);
      lbuffer.vertex(mat, v2.x, v2.y, v2.z).color(color);
      lbuffer.vertex(mat, v2.x, v2.y, v2.z).color(color);
      lbuffer.vertex(mat, v3.x, v3.y, v3.z).color(color);
      lbuffer.vertex(mat, v3.x, v3.y, v3.z).color(color);
      lbuffer.vertex(mat, v0.x, v0.y, v0.z).color(color);
    }

    if (render) {
      RenderSystem.setShader(GameRenderer::getPositionColorProgram);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      BufferRenderer.drawWithGlobalProgram(lbuffer.end());
    }

    RenderSystem.disableBlend();
    RenderSystem.enableCull();
  }

  private void buildVerticesWall(BufferBuilder buffer, Matrix4f mat, Wall wall, int color){
      Vector3f v0 = wall.getVertex0();
      Vector3f v1 = wall.getVertex1();
      Vector3f v2 = wall.getVertex2();
      Vector3f v3 = wall.getVertex3();
      buffer.vertex(mat, v0.x, v0.y, v0.z).color(color);
      buffer.vertex(mat, v1.x, v1.y, v1.z).color(color);
      buffer.vertex(mat, v2.x, v2.y, v2.z).color(color);

      buffer.vertex(mat, v0.x, v0.y, v0.z).color(color);
      buffer.vertex(mat, v2.x, v2.y, v2.z).color(color);
      buffer.vertex(mat, v3.x, v3.y, v3.z).color(color);
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

  enum WallType {
    UP,
    DOWN,
    NORTH,
    EAST,
    SOUTH,
    WEST
  }

  private class Wall implements Comparable<Wall> {
     private final static float adjustment = .01f;

     protected Vec3i block;
     protected WallType type;
     private Vector3f v0, v1, v2, v3;
     
     Wall(Vec3i block, WallType type){
       this.block = block;
       this.type = type;
       float minX = block.getX();
       float minY = block.getY();
       float minZ = block.getZ();
       float maxX = minX + 1.0f;
       float maxY = minY + 1.0f;
       float maxZ = minZ + 1.0f;
       switch (type) {
         case EAST:
	   v0 = new Vector3f(maxX-adjustment, minY, minZ);
	   v1 = new Vector3f(maxX-adjustment, minY, maxZ);
	   v2 = new Vector3f(maxX-adjustment, maxY, maxZ);
	   v3 = new Vector3f(maxX-adjustment, maxY, minZ);
	   break;
	 case WEST:
	   v0 = new Vector3f(minX+adjustment, minY, minZ);
	   v1 = new Vector3f(minX+adjustment, minY, maxZ);
	   v2 = new Vector3f(minX+adjustment, maxY, maxZ);
	   v3 = new Vector3f(minX+adjustment, maxY, minZ);
	   break;
	 case UP:
	   v0 = new Vector3f(minX, maxY-adjustment, minZ);
	   v1 = new Vector3f(minX, maxY-adjustment, maxZ);
	   v2 = new Vector3f(maxX, maxY-adjustment, maxZ);
	   v3 = new Vector3f(maxX, maxY-adjustment, minZ);
	   break;
	 case DOWN:
	   v0 = new Vector3f(minX, minY+adjustment, minZ);
	   v1 = new Vector3f(minX, minY+adjustment, maxZ);
	   v2 = new Vector3f(maxX, minY+adjustment, maxZ);
	   v3 = new Vector3f(maxX, minY+adjustment, minZ);
	   break;
	 case NORTH:
	   v0 = new Vector3f(minX, minY, maxZ-adjustment);
	   v1 = new Vector3f(minX, maxY, maxZ-adjustment);
	   v2 = new Vector3f(maxX, maxY, maxZ-adjustment);
	   v3 = new Vector3f(maxX, minY, maxZ-adjustment);
	   break;
	 case SOUTH:
	   v0 = new Vector3f(minX, minY, minZ+adjustment);
	   v1 = new Vector3f(minX, maxY, minZ+adjustment);
	   v2 = new Vector3f(maxX, maxY, minZ+adjustment);
	   v3 = new Vector3f(maxX, minY, minZ+adjustment);
	   break;
       }
     }

     public Vector3f getVertex0() {
       return v0;
     }

     public Vector3f getVertex1() {
       return v1;
     }

     public Vector3f getVertex2() {
       return v2;
     }

     public Vector3f getVertex3() {
       return v3;
     }

     public boolean isWithinDistance(Position pos, double distance) {
       return block.isWithinDistance(pos, distance);
     }

     public int hashCode() {
       int ord = type.ordinal();
       return block.hashCode() ^ ord;
     }

     public boolean equals(Object obj) {
       if (null == obj) return false;
       if (!(obj instanceof Wall)) return false;
       Wall wall = (Wall) obj;
       return block.equals(wall.block) && (type == wall.type);
     }
     public int compareTo(Wall wall) {
       int compare = block.compareTo(wall.block);
       if (0 == compare)
         return type.compareTo(wall.type);
       return compare;
     }
  }

  private class WallGroup {
    public Wall up, down, north, east, south, west;

    WallGroup(Vec3i block){
      up = new Wall(block.add(0, -1, 0), WallType.UP);
      down = new Wall(block.add(0, 1, 0), WallType.DOWN);
      north = new Wall(block.add(0, 0, -1), WallType.NORTH);
      east = new Wall(block.add(-1, 0, 0), WallType.EAST);
      south = new Wall(block.add(0, 0, 1), WallType.SOUTH);
      west = new Wall(block.add(1, 0, 0), WallType.WEST);
    }
  }
}
