package com.github.sbobicus;

import org.joml.Vector3f;

public class WallGroup {
    protected Vector3f v000, v001, v010, v011, v100, v101, v110, v111;
    protected WallVertexGroup west, east, down, up, south, north;
    protected int size;

    public WallGroup() {
       this(false, false, false, false, false, false);
    }

    public WallGroup(boolean pWest, boolean pEast, boolean pDown, boolean pUp, boolean pSouth, boolean pNorth) {
       west = pWest ? new WallVertexGroup() : null;
       east = pEast ? new WallVertexGroup() : null;
       down = pDown ? new WallVertexGroup() : null;
       up = pUp ? new WallVertexGroup() : null;
       south = pSouth ? new WallVertexGroup() : null;
       north = pNorth ? new WallVertexGroup() : null;
       size = 0;
       size = pWest ? size : size + 1;
       size = pEast ? size : size + 1;
       size = pDown ? size : size + 1;
       size = pUp ? size : size + 1;
       size = pSouth ? size : size + 1;
       size = pNorth ? size : size + 1;
       v000 = null;
       v001 = null;
       v010 = null;
       v011 = null;
       v100 = null;
       v101 = null;
       v110 = null;
       v111 = null;
    }

    public int getSize() {
       return size;
    }

    public WallVertexGroup getWestWall() {
       if (west != null)
          west.updateGroup(v000, v001, v010, v011);
       return west;
    }

    public WallVertexGroup getEastWall() {
       if (east != null)
          east.updateGroup(v100, v101, v110, v111);
       return east;
    }

    public WallVertexGroup getDownWall() {
       if (down != null)
          down.updateGroup(v000, v001, v100, v101);
       return down;
    }

    public WallVertexGroup getUpWall() {
       if (up != null)
          up.updateGroup(v010, v011, v110, v111);
       return up;
    }

    public WallVertexGroup getSouthWall() {
       if (south != null)
          south.updateGroup(v000, v010, v100, v110);
       return south;
    }

    public WallVertexGroup getNorthWall() {
       if (north != null)
          north.updateGroup(v001, v011, v101, v111);
       return north;
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
