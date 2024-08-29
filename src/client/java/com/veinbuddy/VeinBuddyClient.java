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
  private Map<Vec3i, VertexGroup> selectionVertices = new ConcurrentHashMap<Vec3i, VertexGroup>();

  private Set<Vec3i> digSet = new ConcurrentSkipListSet<Vec3i>();
  private Set<Vec3i> wallBlocks = new ConcurrentSkipListSet<Vec3i>();
  private Map<Vec3i, WallGroup> wallBlockWalls = new ConcurrentHashMap<Vec3i, WallGroup>();
  private Map<Vec3i, VertexGroup> wallBlockVertices = new ConcurrentHashMap<Vec3i, VertexGroup>();

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
      VertexGroup group = new VertexGroup();
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
      group.v000 = new Vector3f(minX, minY, minZ);
      group.v001 = new Vector3f(minX, minY, maxZ);
      group.v010 = new Vector3f(minX, maxY, minZ);
      group.v011 = new Vector3f(minX, maxY, maxZ);
      group.v100 = new Vector3f(maxX, minY, minZ);
      group.v101 = new Vector3f(maxX, minY, maxZ);
      group.v110 = new Vector3f(maxX, maxY, minZ);
      group.v111 = new Vector3f(maxX, maxY, maxZ);
      selectionVertices.put(selection, group);
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
      if (pos.distanceTo(center) > visibilityRange)
	continue;
      if (!rayIntersectsSphere(pos, dir, center))
        continue;
      sIter.remove();
      selectionVertices.remove(selectedBlock);
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

      if (!digSet.contains(block)){
	wallBlockWalls.remove(block);
	iter.remove();
	continue;
      }

      WallGroup group = wallBlockWalls.get(block);
      if (null == group) {
        group = new WallGroup(block);
        wallBlockWalls.put(block, group);
      }

      boolean west =  !digSet.contains(block.add(-1,  0,  0));
      boolean east =  !digSet.contains(block.add( 1,  0,  0));
      boolean down =  !digSet.contains(block.add( 0, -1,  0));
      boolean up =    !digSet.contains(block.add( 0,  1,  0));
      boolean south = !digSet.contains(block.add( 0,  0, -1));
      boolean north = !digSet.contains(block.add( 0,  0,  1));

      if (up)
        group.add(WallType.UP);
      if (down)
        group.add(WallType.DOWN);
      if (north)
        group.add(WallType.NORTH);
      if (east)
        group.add(WallType.EAST);
      if (south)
        group.add(WallType.SOUTH);
      if (west)
        group.add(WallType.WEST);

      if (!(up || down || north || east || south || west)){
	wallBlockWalls.remove(block);
	iter.remove();
	continue;
      }

      VertexGroup vertexGroup = wallBlockVertices.get(block);
      if (null == vertexGroup) {
        vertexGroup = new VertexGroup();
        wallBlockVertices.put(block, vertexGroup);
      }

      float minX = block.getX();
      float minY = block.getY();
      float minZ = block.getZ();
      float maxX = minX + 1.0f;
      float maxY = minY + 1.0f;
      float maxZ = minZ + 1.0f;
      
      float adjustment = 0.01f;
      float xValEast = maxX-adjustment;
      float yValUp = maxY-adjustment;
      float zValNorth = maxZ-adjustment;
      float xValWest = minX+adjustment;
      float yValDown = minY+adjustment;
      float zValSouth = minZ+adjustment;

      float xValEastDig = maxX+adjustment;
      float yValUpDig = maxY+adjustment;
      float zValNorthDig = maxZ+adjustment;
      float xValWestDig = minX-adjustment;
      float yValDownDig = minY-adjustment;
      float zValSouthDig = minZ-adjustment;

      if (up) {
	vertexGroup.v010 = new Vector3f(minX, yValUp, minZ);
	vertexGroup.v011 = new Vector3f(minX, yValUp, maxZ);
	vertexGroup.v110 = new Vector3f(maxX, yValUp, minZ);
	vertexGroup.v111 = new Vector3f(maxX, yValUp, maxZ);
	if (digSet.contains(block.add(0, 1, 1))) {
          vertexGroup.v011 = new Vector3f(minX, yValUp, zValNorthDig);
          vertexGroup.v111 = new Vector3f(maxX, yValUp, zValNorthDig);
	}
	if (digSet.contains(block.add(1, 1, 0))) {
          vertexGroup.v110 = new Vector3f(xValEastDig, yValUp, minZ);
          vertexGroup.v111 = new Vector3f(xValEastDig, yValUp, maxZ);
        }
	if (digSet.contains(block.add(0, 1, -1))) {
          vertexGroup.v010 = new Vector3f(minX, yValUp, zValSouthDig);
          vertexGroup.v110 = new Vector3f(maxX, yValUp, zValSouthDig);
	}
	if (digSet.contains(block.add(-1, 1, 0))) {
          vertexGroup.v010 = new Vector3f(xValWestDig, yValUp, minZ);
          vertexGroup.v011 = new Vector3f(xValWestDig, yValUp, maxZ);
        }
      }
      if (down) {
	vertexGroup.v000 = new Vector3f(minX, yValDown, minZ);
	vertexGroup.v001 = new Vector3f(minX, yValDown, maxZ);
	vertexGroup.v100 = new Vector3f(maxX, yValDown, minZ);
	vertexGroup.v101 = new Vector3f(maxX, yValDown, maxZ);
	if (digSet.contains(block.add(0, -1, 1))) {
          vertexGroup.v001 = new Vector3f(minX, yValDown, zValNorthDig);
          vertexGroup.v101 = new Vector3f(maxX, yValDown, zValNorthDig);
	}
	if (digSet.contains(block.add(1, -1, 0))) {
          vertexGroup.v100 = new Vector3f(xValEastDig, yValDown, minZ);
          vertexGroup.v101 = new Vector3f(xValEastDig, yValDown, maxZ);
        }
	if (digSet.contains(block.add(0, -1, -1))) {
          vertexGroup.v000 = new Vector3f(minX, yValDown, zValSouthDig);
          vertexGroup.v100 = new Vector3f(maxX, yValDown, zValSouthDig);
	}
	if (digSet.contains(block.add(-1, -1, 0))) {
          vertexGroup.v000 = new Vector3f(xValWestDig, yValDown, minZ);
          vertexGroup.v001 = new Vector3f(xValWestDig, yValDown, maxZ);
        }
      }
      if (north) {
	vertexGroup.v001 = new Vector3f(minX, minY, zValNorth);
	vertexGroup.v011 = new Vector3f(minX, maxY, zValNorth);
	vertexGroup.v101 = new Vector3f(maxX, minY, zValNorth);
	vertexGroup.v111 = new Vector3f(maxX, maxY, zValNorth);
	if (digSet.contains(block.add(0, 1, 1))) {
          vertexGroup.v011 = new Vector3f(minX, yValUpDig, zValNorth);
          vertexGroup.v111 = new Vector3f(maxX, yValUpDig, zValNorth);
	}
	if (digSet.contains(block.add(1, 0, 1))) {
          vertexGroup.v101 = new Vector3f(xValEastDig, minY, zValNorth);
          vertexGroup.v111 = new Vector3f(xValEastDig, maxY, zValNorth);
        }
	if (digSet.contains(block.add(0, -1, 1))) {
          vertexGroup.v001 = new Vector3f(minX, yValDownDig, zValNorth);
          vertexGroup.v101 = new Vector3f(maxX, yValDownDig, zValNorth);
	}
	if (digSet.contains(block.add(-1, 0, 1))) {
          vertexGroup.v001 = new Vector3f(xValWestDig, minY, zValNorth);
          vertexGroup.v011 = new Vector3f(xValWestDig, maxY, zValNorth);
        }
      }
      if (east) {
        vertexGroup.v100 = new Vector3f(xValEast, minY, minZ);
        vertexGroup.v101 = new Vector3f(xValEast, minY, maxZ);
        vertexGroup.v110 = new Vector3f(xValEast, maxY, minZ);
        vertexGroup.v111 = new Vector3f(xValEast, maxY, maxZ);
	if (digSet.contains(block.add(1, 1, 0))) {
          vertexGroup.v110 = new Vector3f(xValEast, yValUpDig, minZ);
          vertexGroup.v111 = new Vector3f(xValEast, yValUpDig, maxZ);
	}
	if (digSet.contains(block.add(1, 0, 1))) {
          vertexGroup.v101 = new Vector3f(xValEast, minY, zValNorthDig);
          vertexGroup.v111 = new Vector3f(xValEast, maxY, zValNorthDig);
        }
	if (digSet.contains(block.add(1, -1, 0))) {
          vertexGroup.v100 = new Vector3f(xValEast, yValDownDig, minZ);
          vertexGroup.v101 = new Vector3f(xValEast, yValDownDig, maxZ);
	}
	if (digSet.contains(block.add(1, 0, -1))) {
          vertexGroup.v100 = new Vector3f(xValEast, minY, zValSouthDig);
          vertexGroup.v110 = new Vector3f(xValEast, maxY, zValSouthDig);
        }
      }
      if (south) {
	vertexGroup.v000 = new Vector3f(minX, minY, zValSouth);
	vertexGroup.v010 = new Vector3f(minX, maxY, zValSouth);
	vertexGroup.v100 = new Vector3f(maxX, minY, zValSouth);
	vertexGroup.v110 = new Vector3f(maxX, maxY, zValSouth);
	if (digSet.contains(block.add(0, 1, -1))) {
          vertexGroup.v010 = new Vector3f(minX, yValUpDig, zValSouth);
          vertexGroup.v110 = new Vector3f(maxX, yValUpDig, zValSouth);
	}
	if (digSet.contains(block.add(1, 0, -1))) {
          vertexGroup.v100 = new Vector3f(xValEastDig, minY, zValSouth);
          vertexGroup.v110 = new Vector3f(xValEastDig, maxY, zValSouth);
        }
	if (digSet.contains(block.add(0, -1, -1))) {
          vertexGroup.v000 = new Vector3f(minX, yValDownDig, zValSouth);
          vertexGroup.v100 = new Vector3f(maxX, yValDownDig, zValSouth);
	}
	if (digSet.contains(block.add(-1, 0, -1))) {
          vertexGroup.v000 = new Vector3f(xValWestDig, minY, zValSouth);
          vertexGroup.v010 = new Vector3f(xValWestDig, maxY, zValSouth);
        }
      }
      if (west) {
        vertexGroup.v000 = new Vector3f(xValWest, minY, minZ);
        vertexGroup.v001 = new Vector3f(xValWest, minY, maxZ);
        vertexGroup.v010 = new Vector3f(xValWest, maxY, minZ);
        vertexGroup.v011 = new Vector3f(xValWest, maxY, maxZ);
	if (digSet.contains(block.add(-1, 1, 0))) {
          vertexGroup.v010 = new Vector3f(xValWest, yValUpDig, minZ);
          vertexGroup.v011 = new Vector3f(xValWest, yValUpDig, maxZ);
	}
	if (digSet.contains(block.add(-1, 0, 1))) {
          vertexGroup.v001 = new Vector3f(xValWest, minY, zValNorthDig);
          vertexGroup.v011 = new Vector3f(xValWest, maxY, zValNorthDig);
        }
	if (digSet.contains(block.add(-1, -1, 0))) {
          vertexGroup.v000 = new Vector3f(xValWest, yValDownDig, minZ);
          vertexGroup.v001 = new Vector3f(xValWest, yValDownDig, maxZ);
	}
	if (digSet.contains(block.add(-1, 0, -1))) {
          vertexGroup.v000 = new Vector3f(xValWest, minY, zValSouthDig);
          vertexGroup.v010 = new Vector3f(xValWest, maxY, zValSouthDig);
        }
      }

      if (up && north) {
	vertexGroup.v011 = new Vector3f(minX, yValUp, zValNorth);
	vertexGroup.v111 = new Vector3f(maxX, yValUp, zValNorth);
      }
      if (up && east) {
	vertexGroup.v110 = new Vector3f(xValEast, yValUp, minZ);
	vertexGroup.v111 = new Vector3f(xValEast, yValUp, maxZ);
      }
      if (up && south) {
	vertexGroup.v010 = new Vector3f(minX, yValUp, zValSouth);
	vertexGroup.v110 = new Vector3f(maxX, yValUp, zValSouth);
      }
      if (up && west) {
	vertexGroup.v010 = new Vector3f(xValWest, yValUp, minZ);
	vertexGroup.v011 = new Vector3f(xValWest, yValUp, maxZ);
      }

      if (down && north) {
	vertexGroup.v001 = new Vector3f(minX, yValDown, zValNorth);
	vertexGroup.v101 = new Vector3f(maxX, yValDown, zValNorth);
      }
      if (down && east) {
	vertexGroup.v100 = new Vector3f(xValEast, yValDown, minZ);
	vertexGroup.v101 = new Vector3f(xValEast, yValDown, maxZ);
      }
      if (down && south) {
	vertexGroup.v000 = new Vector3f(minX, yValDown, zValSouth);
	vertexGroup.v100 = new Vector3f(maxX, yValDown, zValSouth);
      }
      if (down && west) {
	vertexGroup.v000 = new Vector3f(xValWest, yValDown, minZ);
	vertexGroup.v001 = new Vector3f(xValWest, yValDown, maxZ);
      }

      if (north && east) {
	vertexGroup.v101 = new Vector3f(xValEast, minY, zValNorth);
	vertexGroup.v111 = new Vector3f(xValEast, maxY, zValNorth);
      }
      if (south && east) {
	vertexGroup.v100 = new Vector3f(xValEast, minY, zValSouth);
	vertexGroup.v110 = new Vector3f(xValEast, maxY, zValSouth);
      }
      if (south && west) {
	vertexGroup.v000 = new Vector3f(xValWest, minY, zValSouth);
	vertexGroup.v010 = new Vector3f(xValWest, maxY, zValSouth);
      }
      if (north && west) {
	vertexGroup.v001 = new Vector3f(xValWest, minY, zValNorth);
	vertexGroup.v011 = new Vector3f(xValWest, maxY, zValNorth);
      }
    }
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
      VertexGroup group = selectionVertices.get(block);
      buildVerticesWall(buffer, mat, group, WallType.UP, color);
      buildVerticesWall(buffer, mat, group, WallType.DOWN, color);
      buildVerticesWall(buffer, mat, group, WallType.NORTH, color);
      buildVerticesWall(buffer, mat, group, WallType.EAST, color);
      buildVerticesWall(buffer, mat, group, WallType.SOUTH, color);
      buildVerticesWall(buffer, mat, group, WallType.WEST, color);
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
    VertexGroup g = wallBlockVertices.get(block);
    boolean up, down, north, east, south, west; 
    up = null != group.up;
    down = null != group.down;
    north = null != group.north;
    east = null != group.east;
    south = null != group.south;
    west = null != group.west;

    if (up)
      buildVerticesWall(buffer, mat, g, WallType.UP, color);
    if (down)
      buildVerticesWall(buffer, mat, g, WallType.DOWN, color);
    if (north)
      buildVerticesWall(buffer, mat, g, WallType.NORTH, color);
    if (east)
      buildVerticesWall(buffer, mat, g, WallType.EAST, color);
    if (south)
      buildVerticesWall(buffer, mat, g, WallType.SOUTH, color);
    if (west)
      buildVerticesWall(buffer, mat, g, WallType.WEST, color);
  }

  private void buildVerticesWall(BufferBuilder buffer, Matrix4f mat, VertexGroup g, WallType type, int color) {
    Vector3f v0 = g.getVertex0(type);
    Vector3f v1 = g.getVertex1(type);
    Vector3f v2 = g.getVertex2(type);
    Vector3f v3 = g.getVertex3(type);
    buffer.vertex(mat, v0.x, v0.y, v0.z).color(color);
    buffer.vertex(mat, v1.x, v1.y, v1.z).color(color);
    buffer.vertex(mat, v2.x, v2.y, v2.z).color(color);

    buffer.vertex(mat, v0.x, v0.y, v0.z).color(color);
    buffer.vertex(mat, v2.x, v2.y, v2.z).color(color);
    buffer.vertex(mat, v3.x, v3.y, v3.z).color(color);
  }

  private void buildVerticesWallBlockOutline(BufferBuilder buffer, Matrix4f mat, Vec3i block, int color) {
    WallGroup group = wallBlockWalls.get(block);
    VertexGroup g = wallBlockVertices.get(block);
    boolean up, down, north, east, south, west; 
    up = null != group.up;
    down = null != group.down;
    north = null != group.north;
    east = null != group.east;
    south = null != group.south;
    west = null != group.west;

    if (up)
      buildVerticesWallOutline(buffer, mat, g, WallType.UP, color);
    if (down)
      buildVerticesWallOutline(buffer, mat, g, WallType.DOWN, color);
    if (north)
      buildVerticesWallOutline(buffer, mat, g, WallType.NORTH, color);
    if (east)
      buildVerticesWallOutline(buffer, mat, g, WallType.EAST, color);
    if (south)
      buildVerticesWallOutline(buffer, mat, g, WallType.SOUTH, color);
    if (west)
      buildVerticesWallOutline(buffer, mat, g, WallType.WEST, color);
  }

  private void buildVerticesWallOutline(BufferBuilder buffer, Matrix4f mat, VertexGroup g, WallType type, int color) {
    Vector3f v0 = g.getVertex0(type);
    Vector3f v1 = g.getVertex1(type);
    Vector3f v2 = g.getVertex2(type);
    Vector3f v3 = g.getVertex3(type);
    buffer.vertex(mat, v0.x, v0.y, v0.z).color(color);
    buffer.vertex(mat, v1.x, v1.y, v1.z).color(color);
    buffer.vertex(mat, v1.x, v1.y, v1.z).color(color);
    buffer.vertex(mat, v2.x, v2.y, v2.z).color(color);
    buffer.vertex(mat, v2.x, v2.y, v2.z).color(color);
    buffer.vertex(mat, v3.x, v3.y, v3.z).color(color);
    buffer.vertex(mat, v3.x, v3.y, v3.z).color(color);
    buffer.vertex(mat, v0.x, v0.y, v0.z).color(color);
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

  private class Wall {
     protected Vec3i block;
     protected WallType type;
     
     Wall(Vec3i block, WallType type){
       this.block = block;
       this.type = type;
     }
  }

  private class WallGroup {
    protected Vec3i block;
    public Wall up, down, north, east, south, west;

    WallGroup(Vec3i block){
      this.block = block;
      up = null;
      down = null;
      north = null;
      east = null;
      south = null;
      west = null;
    }

    public void add(WallType type) {
      switch (type) {
	case WallType.UP:
          if (null == up)
            up = new Wall(block, WallType.UP);
	  break;
	case WallType.DOWN:
          if (null == down)
            down = new Wall(block, WallType.DOWN);
	  break;
	case WallType.NORTH:
          if (null == north)
            north = new Wall(block, WallType.NORTH);
	  break;
	case WallType.EAST:
          if (null == east)
            east = new Wall(block, WallType.EAST);
	  break;
	case WallType.SOUTH:
          if (null == south)
            south = new Wall(block, WallType.SOUTH);
	  break;
	case WallType.WEST:
          if (null == west)
            west = new Wall(block, WallType.WEST);
	  break;
      }
    }

    public void remove(WallType type) {
      switch (type) {
	case WallType.UP:
          up = null;
	  break;
	case WallType.DOWN:
          down = null;
	  break;
	case WallType.NORTH:
	  north = null;
	  break;
	case WallType.EAST:
	  east = null;
	  break;
	case WallType.SOUTH:
	  south = null;
	  break;
	case WallType.WEST:
	  west = null;
	  break;
      }
    }
  }

  private class VertexGroup {
    public Vector3f v000, v001, v010, v011, v100, v101, v110, v111;

    VertexGroup(){
      v000 = null;
      v001 = null;
      v010 = null;
      v011 = null;
      v100 = null;
      v101 = null;
      v110 = null;
      v111 = null;
    }

    public Vector3f getVertex0(WallType type) {
      switch (type) {
        case WallType.UP:
        case WallType.NORTH:
        case WallType.EAST:
	  return v111;
        case WallType.DOWN:
        case WallType.SOUTH:
        case WallType.WEST:
	  return v000;
      }
      return v000;
    }
    public Vector3f getVertex1(WallType type) {
      switch (type) {
        case WallType.UP:
        case WallType.NORTH:
	  return v011;
        case WallType.EAST:
	  return v101;
        case WallType.DOWN:
        case WallType.WEST:
	  return v001;
        case WallType.SOUTH:
	  return v010;
      }
      return v000;
    }

    public Vector3f getVertex2(WallType type) {
      switch (type) {
        case WallType.UP:
	  return v010;
	case WallType.NORTH:
	  return v001;
	case WallType.EAST:
	  return v100;
	case WallType.DOWN:
	  return v101;
	case WallType.WEST:
	  return v011;
	case WallType.SOUTH:
	  return v110;
      }
      return v000;
    }

    public Vector3f getVertex3(WallType type) {
      switch (type) {
        case WallType.UP:
	case WallType.EAST:
	  return v110;
	case WallType.NORTH:
	  return v101;
	case WallType.DOWN:
	case WallType.SOUTH:
	  return v100;
	case WallType.WEST:
	  return v010;
      }
      return v000;
    }
  }
}
