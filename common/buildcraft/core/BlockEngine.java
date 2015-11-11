package buildcraft.core;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import buildcraft.core.lib.engines.BlockEngineBase;
import buildcraft.core.lib.engines.TileEngineBase;
import buildcraft.core.lib.utils.IModelRegister;
import buildcraft.core.lib.utils.ModelHelper;

public class BlockEngine extends BlockEngineBase implements IModelRegister {
    private final List<Class<? extends TileEngineBase>> engineTiles;
    private final List<String> names;

    public BlockEngine() {
        super();
        setUnlocalizedName("engineBlock");

        engineTiles = new ArrayList<Class<? extends TileEngineBase>>(16);
        names = new ArrayList<String>(16);
    }

    @Override
    public String getUnlocalizedName(int metadata) {
        return names.get(metadata % names.size());
    }

    public void registerTile(Class<? extends TileEngineBase> engineTile, String name) {
        engineTiles.add(engineTile);
        names.add(name);
    }

    @Override
    public TileEntity createTileEntity(World world, int metadata) {
        try {
            return engineTiles.get(metadata % engineTiles.size()).newInstance();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public void getSubBlocks(Item item, CreativeTabs par2CreativeTabs, List itemList) {
        for (int i = 0; i < engineTiles.size(); i++) {
            itemList.add(new ItemStack(this, 1, i));
        }
    }

    public int getEngineCount() {
        return engineTiles.size();
    }

    @Override
    public void registerModels() {
        Item item = ItemBlock.getItemFromBlock(this);
        ModelHelper.registerItemModel(item, 0, "_wood");
        ModelHelper.registerItemModel(item, 1, "_stone");
        ModelHelper.registerItemModel(item, 2, "_iron");
        ModelHelper.registerItemModel(item, 3, "_creative");
    }

    @Override
    public double getBreathingCoefficent() {
        return 1;
    }
}
