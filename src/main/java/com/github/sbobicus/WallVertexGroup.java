package com.github.sbobicus;

import org.joml.Vector3f;

public class WallVertexGroup {
    public Vector3f v00, v01, v10, v11;

    public WallVertexGroup() {}

    public void updateGroup(Vector3f v00, Vector3f v01, Vector3f v10, Vector3f v11) {
       this.v00 = v00;
       this.v01 = v01;
       this.v10 = v10;
       this.v11 = v11;
    }
 }
