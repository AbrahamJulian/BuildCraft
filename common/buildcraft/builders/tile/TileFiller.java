/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.tile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import buildcraft.api.core.EnumPipePart;
import buildcraft.api.core.IAreaProvider;
import buildcraft.api.core.IBox;
import buildcraft.api.filler.IFillerPattern;
import buildcraft.api.inventory.IItemTransactor;
import buildcraft.api.mj.MjAPI;
import buildcraft.api.mj.MjBattery;
import buildcraft.api.mj.MjCapabilityHelper;
import buildcraft.api.statements.IStatementParameter;
import buildcraft.api.statements.containers.IFillerStatementContainer;
import buildcraft.api.tiles.IControllable;
import buildcraft.api.tiles.IDebuggable;
import buildcraft.api.tiles.TilesAPI;

import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.mj.MjBatteryReceiver;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.PacketBufferBC;
import buildcraft.lib.statement.FullStatement;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;

import buildcraft.builders.addon.AddonFillingPlanner;
import buildcraft.builders.filler.FillerType;
import buildcraft.builders.filler.Filling;
import buildcraft.builders.snapshot.ITileForTemplateBuilder;
import buildcraft.builders.snapshot.ItemBlocks;
import buildcraft.builders.snapshot.SnapshotBuilder;
import buildcraft.builders.snapshot.Template;
import buildcraft.builders.snapshot.Template.BuildingInfo;
import buildcraft.builders.snapshot.TemplateBuilder;
import buildcraft.core.marker.volume.ClientVolumeBoxes;
import buildcraft.core.marker.volume.EnumAddonSlot;
import buildcraft.core.marker.volume.Lock;
import buildcraft.core.marker.volume.VolumeBox;
import buildcraft.core.marker.volume.WorldSavedDataVolumeBoxes;

public class TileFiller extends TileBC_Neptune
    implements ITickable, IDebuggable, ITileForTemplateBuilder, IFillerStatementContainer, IControllable {

    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("filler");
    public static final int NET_CAN_EXCAVATE = IDS.allocId("CAN_EXCAVATE");
    public static final int NET_INVERT = IDS.allocId("INVERT");
    public static final int NET_PATTERN = IDS.allocId("PATTERN");
    public static final int NET_BOX = IDS.allocId("BOX");

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public final ItemHandlerSimple invResources =
        itemManager.addInvHandler(
            "resources",
            27,
            (slot, stack) -> ItemBlocks.getList().contains(stack.getItem()),
            EnumAccess.INSERT,
            EnumPipePart.VALUES
        );
    private final MjBattery battery = new MjBattery(1000 * MjAPI.MJ);
    private boolean canExcavate = true;
    private boolean inverted = false;
    private boolean finished = false;
    private byte lockedTicks = 0;
    private Mode mode = Mode.ON;

    public final Box box = new Box();
    public AddonFillingPlanner addon;
    public boolean markerBox = false;

    public final FullStatement<IFillerPattern> patternStatement;
    private BuildingInfo buildingInfo;
    public TemplateBuilder builder = new TemplateBuilder(this);

    public TileFiller() {
        patternStatement = new FullStatement<>(FillerType.INSTANCE, 4, this::onStatementChange);
        caps.addProvider(new MjCapabilityHelper(new MjBatteryReceiver(battery)));
        caps.addCapabilityInstance(TilesAPI.CAP_CONTROLLABLE, this, EnumPipePart.VALUES);
    }

    @Override
    public void onPlacedBy(EntityLivingBase placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        if (world.isRemote) {
            return;
        }
        IBlockState blockState = world.getBlockState(pos);
        WorldSavedDataVolumeBoxes volumeBoxes = WorldSavedDataVolumeBoxes.get(world);
        BlockPos offsetPos = pos.offset(blockState.getValue(BlockBCBase_Neptune.PROP_FACING).getOpposite());
        VolumeBox volumeBox = volumeBoxes.getBoxAt(offsetPos);
        TileEntity tile = world.getTileEntity(offsetPos);
        if (volumeBox != null) {
            addon = (AddonFillingPlanner) volumeBox.addons
                .values()
                .stream()
                .filter(AddonFillingPlanner.class::isInstance)
                .findFirst()
                .orElse(null);
            if (addon != null) {
                volumeBox.locks.add(
                    new Lock(
                        new Lock.Cause.CauseBlock(pos, blockState.getBlock()),
                        new Lock.Target.TargetAddon(addon.getSlot()),
                        new Lock.Target.TargetResize(),
                        new Lock.Target.TargetUsedByMachine(
                            Lock.Target.TargetUsedByMachine.EnumType.STRIPES_WRITE
                        )
                    )
                );
                volumeBoxes.markDirty();
                addon.updateBuildingInfo();
                patternStatement.set(addon.patternStatement.get());
                for (int p = 0; p < patternStatement.getParamCount(); p++) {
                    addon.patternStatement.set(p, patternStatement.get(p));
                }
            } else {
                box.reset();
                box.setMin(volumeBox.box.min());
                box.setMax(volumeBox.box.max());
                volumeBox.locks.add(
                    new Lock(
                        new Lock.Cause.CauseBlock(pos, blockState.getBlock()),
                        new Lock.Target.TargetResize(),
                        new Lock.Target.TargetUsedByMachine(
                            Lock.Target.TargetUsedByMachine.EnumType.STRIPES_WRITE
                        )
                    )
                );
                volumeBoxes.markDirty();
                updateBuildingInfo();
            }
        } else if (tile instanceof IAreaProvider) {
            IAreaProvider provider = (IAreaProvider) tile;
            box.reset();
            box.setMin(provider.min());
            box.setMax(provider.max());
            markerBox = true;
            provider.removeFromWorld();
            updateBuildingInfo();
        }
        sendNetworkUpdate(NET_RENDER_DATA);
    }

    @Override
    public void update() {
        if (addon != null) {
            addon.patternStatement.set(patternStatement.get());
            for (int p = 0; p < patternStatement.getParamCount(); p++) {
                addon.patternStatement.set(p, patternStatement.get(p));
            }
        }
        if (world.isRemote) {
            if (isValid()) {
                builder.tick();
            }
            patternStatement.canInteract = !isLocked();
            return;
        }
        sendNetworkUpdate(NET_RENDER_DATA);
        lockedTicks--;
        if (lockedTicks < 0) {
            lockedTicks = 0;
        }
        if (mode == Mode.OFF/* || (mode == Mode.ON && finished)*/) { // TODO: finished
            return;
        }
        if (isValid()) {
            finished = builder.tick();
        }
    }

    @Override
    public void validate() {
        super.validate();
        builder.validate();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        builder.invalidate();
    }

    @Override
    public void writePayload(int id, PacketBufferBC buffer, Side side) {
        super.writePayload(id, buffer, side);
        if (side == Side.SERVER) {
            if (id == NET_RENDER_DATA) {
                builder.writeToByteBuf(buffer);
                writePayload(NET_BOX, buffer, side);
            } else if (id == NET_GUI_DATA || id == NET_GUI_TICK) {
                writePayload(NET_CAN_EXCAVATE, buffer, side);
                writePayload(NET_INVERT, buffer, side);
                writePayload(NET_PATTERN, buffer, side);
                builder.writeToByteBuf(buffer);
                buffer.writeBoolean(finished);
                buffer.writeBoolean(lockedTicks > 0);
                buffer.writeEnumValue(mode);
            } else if (id == NET_BOX) {
                box.writeData(buffer);
                buffer.writeBoolean(markerBox);
                buffer.writeBoolean(addon != null);
                if (addon != null) {
                    buffer.writeUniqueId(addon.box.id);
                    buffer.writeEnumValue(addon.getSlot());
                }
            } else if (id == NET_CAN_EXCAVATE) {
                buffer.writeBoolean(canExcavate);
            } else if (id == NET_INVERT) {
                buffer.writeBoolean(inverted);
            } else if (id == NET_PATTERN) {
                patternStatement.writeToBuffer(buffer);
            }
        }
    }

    @Override
    public void readPayload(int id, PacketBufferBC buffer, Side side, MessageContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == Side.CLIENT) {
            if (id == NET_RENDER_DATA) {
                builder.readFromByteBuf(buffer);
                readPayload(NET_BOX, buffer, side, ctx);
            } else if (id == NET_GUI_DATA || id == NET_GUI_TICK) {
                readPayload(NET_CAN_EXCAVATE, buffer, side, ctx);
                readPayload(NET_INVERT, buffer, side, ctx);
                readPayload(NET_PATTERN, buffer, side, ctx);
                builder.readFromByteBuf(buffer);
                finished = buffer.readBoolean();
                lockedTicks = buffer.readBoolean() ? (byte) 1 : (byte) 0;
                mode = buffer.readEnumValue(Mode.class);
            } else if (id == NET_BOX) {
                box.readData(buffer);
                markerBox = buffer.readBoolean();
                if (buffer.readBoolean()) {
                    UUID boxId = buffer.readUniqueId();
                    VolumeBox volumeBox =
                        world.isRemote
                            ? ClientVolumeBoxes.INSTANCE.boxes.stream()
                                .filter(localVolumeBox -> localVolumeBox.id.equals(boxId)).findFirst()
                                .orElseThrow(NullPointerException::new)
                            : WorldSavedDataVolumeBoxes.get(world).getBoxFromId(boxId);
                    addon = (AddonFillingPlanner) volumeBox.addons.get(buffer.readEnumValue(EnumAddonSlot.class));
                }
            } else if (id == NET_CAN_EXCAVATE) {
                canExcavate = buffer.readBoolean();
            } else if (id == NET_INVERT) {
                inverted = buffer.readBoolean();
            } else if (id == NET_PATTERN) {
                patternStatement.readFromBuffer(buffer);
            }
        }
        if (side == Side.SERVER) {
            if (id == NET_CAN_EXCAVATE) {
                canExcavate = buffer.readBoolean();
                sendNetworkGuiUpdate(NET_CAN_EXCAVATE);
            } else if (id == NET_INVERT) {
                inverted = buffer.readBoolean();
                sendNetworkGuiUpdate(NET_INVERT);
            } else if (id == NET_PATTERN) {
                if (isLocked()) {
                    new FullStatement<>(FillerType.INSTANCE, 4, (a, b) -> {}).readFromBuffer(buffer);
                } else {
                    patternStatement.readFromBuffer(buffer);
                    sendNetworkUpdate(NET_PATTERN);
                    onStatementChange();
                }
            }
        }
    }

    private void updateBuildingInfo() {
        Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::cancel);
        buildingInfo = (hasBox() && addon == null) ? Filling.createBuildingInfo(
            box.min(),
            box.size(),
            patternStatement,
            this,
            IntStream.range(0, patternStatement.maxParams)
                .mapToObj(patternStatement::get)
                .toArray(IStatementParameter[]::new),
            inverted
        ) : null;
        Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::updateSnapshot);
    }

    public void sendCanExcavate(boolean newValue) {
        MessageManager.sendToServer(createMessage(NET_CAN_EXCAVATE, buffer -> buffer.writeBoolean(newValue)));
    }

    public void sendInvert(boolean newValue) {
        MessageManager.sendToServer(createMessage(NET_INVERT, buffer -> buffer.writeBoolean(newValue)));
    }

    private void onStatementChange(FullStatement<?> statement, int paramIndex) {
        onStatementChange();
    }

    private void onStatementChange() {
        createAndSendMessage(NET_PATTERN, patternStatement::writeToBuffer);
        if (addon != null) {
            addon.setPattern(patternStatement.get(), patternStatement.getParameters());
        }
        finished = false;
        updateBuildingInfo();
    }

    // Read-write

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag("battery", battery.serializeNBT());
        nbt.setBoolean("canExcavate", canExcavate);
        nbt.setBoolean("inverted", inverted);
        nbt.setBoolean("finished", finished);
        nbt.setByte("lockedTicks", lockedTicks);
        nbt.setTag("mode", NBTUtilBC.writeEnum(mode));
        nbt.setTag("box", box.writeToNBT());
        if (addon != null) {
            nbt.setUniqueId("addonBoxId", addon.box.id);
            nbt.setTag("addonSlot", NBTUtilBC.writeEnum(addon.getSlot()));
        }
        nbt.setBoolean("markerBox", markerBox);
        nbt.setTag("patternStatement", patternStatement.writeToNbt());
        Optional.ofNullable(getBuilder()).ifPresent(builder -> nbt.setTag("builder", builder.serializeNBT()));
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        battery.deserializeNBT(nbt.getCompoundTag("battery"));
        canExcavate = nbt.getBoolean("canExcavate");
        inverted = nbt.getBoolean("inverted");
        finished = nbt.getBoolean("finished");
        lockedTicks = nbt.getByte("lockedTicks");
        mode = Optional.ofNullable(NBTUtilBC.readEnum(nbt.getTag("mode"), Mode.class)).orElse(Mode.ON);
        box.initialize(nbt.getCompoundTag("box"));
        if (nbt.hasKey("addonBoxId") && nbt.hasKey("addonSlot")) {
            addon = (AddonFillingPlanner) WorldSavedDataVolumeBoxes.get(world)
                .getBoxFromId(nbt.getUniqueId("addonBoxId"))
                .addons
                .get(NBTUtilBC.readEnum(nbt.getTag("addonSlot"), EnumAddonSlot.class));
        }
        markerBox = nbt.getBoolean("markerBox");
        patternStatement.readFromNbt(nbt.getCompoundTag("patternStatement"));
        if (nbt.hasKey("builder")) {
            Optional.ofNullable(getBuilder()).ifPresent(SnapshotBuilder::updateSnapshot);
            Optional.ofNullable(getBuilder()).ifPresent(builder -> builder.deserializeNBT(nbt.getCompoundTag("builder")));
        }
    }

    // Rendering

    @Override
    @SideOnly(Side.CLIENT)
    public boolean hasFastRenderer() {
        return true;
    }

    @SuppressWarnings("NullableProblems")
    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return BoundingBoxUtil.makeFrom(getPos(), box);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return Double.MAX_VALUE;
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, EnumFacing side) {
        left.add("battery = " + battery.getDebugString());
        left.add("box = " + box);
        left.add("pattern = " + patternStatement.get());
        left.add("mode = " + mode);
        left.add("is_finished = " + finished);
        left.add("lockedTicks = " + lockedTicks);
        left.add("addon = " + addon);
    }

    @Override
    public World getWorldBC() {
        return world;
    }

    public int getCountToPlace() {
        return builder == null ? 0 : builder.leftToPlace;
    }

    public int getCountToBreak() {
        return builder == null ? 0 : builder.leftToBreak;
    }

    @Override
    public MjBattery getBattery() {
        return battery;
    }

    @Override
    public BlockPos getBuilderPos() {
        return pos;
    }

    @Override
    public boolean canExcavate() {
        return canExcavate;
    }

    public boolean shouldInvert() {
        return inverted;
    }

    public boolean isFinished() {
        return mode != Mode.LOOP && this.finished;
    }

    public boolean isLocked() {
        return lockedTicks > 0;
    }

    @Override
    public TemplateBuilder getBuilder() {
        return isValid() ? builder : null;
    }

    @Override
    public Template.BuildingInfo getTemplateBuildingInfo() {
        return isValid()
            ? addon != null ? addon.buildingInfo : buildingInfo
            : null;
    }

    @Override
    public IItemTransactor getInvResources() {
        return invResources;
    }

    // IFillerStatementContainer

    @Override
    public TileEntity getTile() {
        return this;
    }

    @Override
    public World getFillerWorld() {
        return world;
    }

    @Override
    public boolean hasBox() {
        return addon != null || box.isInitialized();
    }

    public boolean isValid() {
        return hasBox() && buildingInfo != null;
    }

    @Override
    public IBox getBox() {
        if (!hasBox()) {
            throw new IllegalStateException("Called getBox() when hasBox() returned false!");
        }
        return box.isInitialized() ? box : addon.box.box;
    }

    @Override
    public void setPattern(IFillerPattern pattern, IStatementParameter[] params) {
        patternStatement.set(pattern);
        IntStream.range(0, patternStatement.maxParams).forEach(i -> patternStatement.set(i, params[i]));
        finished = false;
        lockedTicks = 3;
    }

    // IControllable

    @Override
    public Mode getControlMode() {
        return mode;
    }

    @Override
    public void setControlMode(Mode mode) {
        if (this.mode == Mode.OFF && mode != Mode.OFF) {
            finished = false;
        }
        this.mode = mode;
    }
}
