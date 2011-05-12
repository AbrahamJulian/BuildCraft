package net.minecraft.src.buildcraft.transport;

import net.minecraft.src.AxisAlignedBB;
import net.minecraft.src.BlockContainer;
import net.minecraft.src.Material;
import net.minecraft.src.TileEntity;
import net.minecraft.src.World;
import net.minecraft.src.mod_BuildCraftTransport;
import net.minecraft.src.buildcraft.core.Utils;

public abstract class BlockPipe extends BlockContainer {
	
	public BlockPipe(int i, Material material) {
		super(i, material);

		setHardness(0.5F);
	}
	
    public int getRenderType()
    {
        return mod_BuildCraftTransport.pipeModel;
    }
    
    public boolean isOpaqueCube()
    {
        return false;
    }
    
    public boolean renderAsNormalBlock()
    {
        return false;
    }

	@Override
	protected abstract TileEntity getBlockEntity();
	
	public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int i, int j, int k)
 {
		float xMin = Utils.pipeMinSize, xMax = Utils.pipeMaxSize, 
		yMin = Utils.pipeMinSize, yMax = Utils.pipeMaxSize, 
		zMin = Utils.pipeMinSize, zMax = Utils.pipeMaxSize;

		if (Utils.isPipeConnected (world, i - 1, j, k, blockID)) {
			xMin = 0.0F;
		}

		if (Utils.isPipeConnected (world, i + 1, j, k, blockID)) {
			xMax = 1.0F;
		}

		if (Utils.isPipeConnected (world, i, j - 1, k, blockID)) {
			yMin = 0.0F;
		}

		if (Utils.isPipeConnected (world, i, j + 1, k, blockID)) {
			yMax = 1.0F;
		}

		if (Utils.isPipeConnected (world, i, j, k - 1, blockID)) {
			zMin = 0.0F;
		}

		if (Utils.isPipeConnected (world, i, j, k + 1, blockID)) {
			zMax = 1.0F;
		}
    	
    	    
		return AxisAlignedBB.getBoundingBoxFromPool((double) i + xMin,
				(double) j + yMin, (double) k + zMin, (double) i + xMax,
				(double) j + yMax, (double) k + zMax);
    }
	
    public AxisAlignedBB getSelectedBoundingBoxFromPool(World world, int i, int j, int k)
    {
        return getCollisionBoundingBoxFromPool (world, i, j, k);
    }
}
