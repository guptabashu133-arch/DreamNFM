package org.mydreamduels.dreamnfm.screen;

import org.bukkit.block.BlockFace;

/**
 * Where a screen sits in the world and which pixel a player is looking at.
 *
 * (x,y,z) is the block holding the TOP-LEFT frame (as seen by someone
 * looking at the screen). facing is the direction the screen faces (out
 * of the wall). Columns go to the viewer's right, rows go down.
 */
public record Geometry(int x, int y, int z, BlockFace facing, int width, int height) {

    /** How far the map surface sits in front of the wall (item frame thickness). */
    static final double THICKNESS = 1.0 / 16.0;

    public static boolean validFacing(BlockFace f) {
        return f == BlockFace.NORTH || f == BlockFace.SOUTH || f == BlockFace.EAST || f == BlockFace.WEST;
    }

    /** Viewer's right-hand direction for a screen facing this way. */
    public int rightX() {
        return switch (facing) {
            case SOUTH -> 1;
            case NORTH -> -1;
            default -> 0;
        };
    }

    public int rightZ() {
        return switch (facing) {
            case EAST -> -1;
            case WEST -> 1;
            default -> 0;
        };
    }

    public int pixelWidth() {
        return width * 128;
    }

    public int pixelHeight() {
        return height * 128;
    }

    public int cellBlockX(int col) {
        return x + rightX() * col;
    }

    public int cellBlockY(int row) {
        return y - row;
    }

    public int cellBlockZ(int col) {
        return z + rightZ() * col;
    }

    /** Pixel hit, or null. */
    public record Hit(int px, int py, int cell, int localX, int localY, double distance) {
    }

    /**
     * Ray (eye + t * dir) against the screen surface. Only counts when the
     * viewer is in front of the screen and within maxDistance.
     */
    public Hit hit(double ex, double ey, double ez, double dx, double dy, double dz, double maxDistance) {
        double nx = facing.getModX(), nz = facing.getModZ();
        double rx = rightX(), rz = rightZ();
        // surface centre of the top-left cell, then its top-left corner
        double sx = x + 0.5 - nx * 0.5 + nx * THICKNESS;
        double sy = y + 0.5;
        double sz = z + 0.5 - nz * 0.5 + nz * THICKNESS;
        double ox = sx - rx * 0.5, oy = sy + 0.5, oz = sz - rz * 0.5;

        double denom = dx * nx + dz * nz;
        if (denom >= -1e-6) return null; // looking away from (or along) the screen
        double t = ((ox - ex) * nx + (oz - ez) * nz) / denom;
        if (t < 0 || t > maxDistance) return null;
        double hx = ex + dx * t, hy = ey + dy * t, hz = ez + dz * t;
        double u = (hx - ox) * rx + (hz - oz) * rz;
        double v = oy - hy;
        if (u < 0 || v < 0 || u >= width || v >= height) return null;
        int px = (int) Math.floor(u * 128), py = (int) Math.floor(v * 128);
        int col = px / 128, row = py / 128;
        return new Hit(px, py, row * width + col, px % 128, py % 128, t);
    }
}
