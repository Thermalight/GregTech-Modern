package com.gregtechceu.gtceu.api.capability.recipe;

import com.gregtechceu.gtceu.api.gui.widget.TankWidget;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.RecipeHandlerList;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gregtechceu.gtceu.api.recipe.content.SerializerFluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.AbstractMapIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.MapFluidIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.MapFluidTagIngredient;
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic;
import com.gregtechceu.gtceu.api.recipe.ui.GTRecipeTypeUI;
import com.gregtechceu.gtceu.api.transfer.fluid.IFluidHandlerModifiable;
import com.gregtechceu.gtceu.client.TooltipsHandler;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.integration.xei.entry.fluid.FluidEntryList;
import com.gregtechceu.gtceu.integration.xei.entry.fluid.FluidStackList;
import com.gregtechceu.gtceu.integration.xei.entry.fluid.FluidTagList;
import com.gregtechceu.gtceu.integration.xei.handlers.fluid.CycleFluidEntryHandler;
import com.gregtechceu.gtceu.integration.xei.widgets.GTRecipeWidget;
import com.gregtechceu.gtceu.utils.GTHashMaps;
import com.gregtechceu.gtceu.utils.OverlayedTankHandler;
import com.gregtechceu.gtceu.utils.OverlayingFluidStorage;

import com.lowdragmc.lowdraglib.gui.texture.ProgressTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.jei.IngredientIO;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author KilaBash
 * @date 2023/2/20
 * @implNote FluidRecipeCapability
 */
public class FluidRecipeCapability extends RecipeCapability<FluidIngredient> {

    public final static FluidRecipeCapability CAP = new FluidRecipeCapability();

    protected FluidRecipeCapability() {
        super("fluid", 0xFF3C70EE, true, 1, SerializerFluidIngredient.INSTANCE);
    }

    @Override
    public FluidIngredient copyInner(FluidIngredient content) {
        return content.copy();
    }

    @Override
    public FluidIngredient copyWithModifier(FluidIngredient content, ContentModifier modifier) {
        if (content.isEmpty()) return content.copy();
        FluidIngredient copy = content.copy();
        copy.setAmount(modifier.apply(copy.getAmount()));
        return copy;
    }

    @Override
    public List<AbstractMapIngredient> convertToMapIngredient(Object obj) {
        List<AbstractMapIngredient> ingredients = new ObjectArrayList<>(1);
        if (obj instanceof FluidIngredient ingredient) {
            for (FluidIngredient.Value value : ingredient.values) {
                if (value instanceof FluidIngredient.TagValue tagValue) {
                    ingredients.add(new MapFluidTagIngredient(tagValue.getTag()));
                } else {
                    Collection<Fluid> fluids = value.getFluids();
                    for (Fluid fluid : fluids) {
                        ingredients.add(new MapFluidIngredient(
                                new FluidStack(fluid, ingredient.getAmount(), ingredient.getNbt())));
                    }
                }
            }
        } else if (obj instanceof FluidStack stack) {
            ingredients.add(new MapFluidIngredient(stack));
            // noinspection deprecation
            stack.getFluid().builtInRegistryHolder().tags()
                    .forEach(tag -> ingredients.add(new MapFluidTagIngredient(tag)));
        }

        return ingredients;
    }

    @Override
    public List<Object> compressIngredients(Collection<Object> ingredients) {
        List<Object> list = new ObjectArrayList<>(ingredients.size());
        for (Object item : ingredients) {
            if (item instanceof FluidIngredient fluid) {
                boolean isEqual = false;
                for (Object obj : list) {
                    if (obj instanceof FluidIngredient fluidIngredient) {
                        if (fluid.equals(fluidIngredient)) {
                            isEqual = true;
                            break;
                        }
                    } else if (obj instanceof FluidStack fluidStack) {
                        if (fluid.test(fluidStack)) {
                            isEqual = true;
                            break;
                        }
                    }
                }
                if (isEqual) continue;
                list.add(fluid);
            } else if (item instanceof FluidStack fluidStack) {
                boolean isEqual = false;
                for (Object obj : list) {
                    if (obj instanceof FluidIngredient fluidIngredient) {
                        if (fluidIngredient.test(fluidStack)) {
                            isEqual = true;
                            break;
                        }
                    } else if (obj instanceof FluidStack stack) {
                        if (fluidStack.isFluidEqual(stack)) {
                            isEqual = true;
                            break;
                        }
                    }
                }
                if (isEqual) continue;
                list.add(fluidStack);
            }
        }
        return list;
    }

    @Override
    public boolean isRecipeSearchFilter() {
        return true;
    }

    @Override
    public int limitParallel(GTRecipe recipe, IRecipeCapabilityHolder holder, int multiplier) {
        if (holder instanceof ICustomParallel p) return p.limitParallel(recipe, multiplier);
        if (!holder.hasCapabilityProxies()) return 0;

        var handlers = holder.getCapabilitiesFlat(IO.OUT, FluidRecipeCapability.CAP);
        if (handlers.isEmpty()) return 0;

        var outputContents = recipe.getOutputContents(FluidRecipeCapability.CAP);
        if (outputContents.isEmpty()) return multiplier;

        int minMultiplier = 0;
        int maxMultiplier = multiplier;

        if (ConfigHolder.INSTANCE.dev.parallelSwitch) {
            int maxAmount = 0;
            List<FluidIngredient> ingredients = new ArrayList<>(outputContents.size());
            for (var content : outputContents) {
                var ing = FluidRecipeCapability.CAP.of(content.content);
                maxAmount = Math.max(maxAmount, ing.getAmount());
                ingredients.add(ing);
            }
            if (maxAmount != 0) {
                maxMultiplier = multiplier = Math.min(multiplier, Integer.MAX_VALUE / maxAmount);
            }
            while (minMultiplier != maxMultiplier) {
                List<FluidIngredient> copied = new ArrayList<>();
                for (final var ing : ingredients) {
                    copied.add(FluidRecipeCapability.CAP.copyWithModifier(ing, ContentModifier.multiplier(multiplier)));
                }

                for (var handler : handlers) {
                    // noinspection unchecked
                    copied = (List<FluidIngredient>) handler.handleRecipe(IO.OUT, recipe, copied, true);
                    if (copied == null) break;
                }
                int[] bin = ParallelLogic.adjustMultiplier(copied == null, minMultiplier, multiplier, maxMultiplier);
                minMultiplier = bin[0];
                multiplier = bin[1];
                maxMultiplier = bin[2];
            }
            return multiplier;
        }

        OverlayedTankHandler overlayedFluidHandler = new OverlayedTankHandler(handlers.stream()
                        .filter(NotifiableFluidTank.class::isInstance)
                        .map(NotifiableFluidTank.class::cast)
                        .toList());

        List<FluidStack> recipeOutputs = outputContents.stream()
                .map(content -> FluidRecipeCapability.CAP.of(content.getContent()))
                .filter(ingredient -> !ingredient.isEmpty())
                .map(ingredient -> ingredient.getStacks()[0])
                .toList();

        while (minMultiplier != maxMultiplier) {
            overlayedFluidHandler.reset();

            int returnedAmount = 0;
            int amountToInsert = 0;

            for (FluidStack fluidStack : recipeOutputs) {
                if (fluidStack.getAmount() <= 0) continue;
                if (fluidStack.isEmpty()) continue;
                // Since multiplier starts at Int.MAX, check here for integer overflow
                if (multiplier > Integer.MAX_VALUE / fluidStack.getAmount()) {
                    amountToInsert = Integer.MAX_VALUE;
                } else {
                    amountToInsert = fluidStack.getAmount() * multiplier;
                }
                returnedAmount = amountToInsert - overlayedFluidHandler.tryFill(fluidStack, amountToInsert);
                if (returnedAmount > 0) {
                    break;
                }
            }

            int[] bin = ParallelLogic.adjustMultiplier(returnedAmount == 0, minMultiplier, multiplier, maxMultiplier);
            minMultiplier = bin[0];
            multiplier = bin[1];
            maxMultiplier = bin[2];

        }
        return multiplier;
    }

    @Override
    public int getMaxParallelRatio(IRecipeCapabilityHolder holder, GTRecipe recipe, int parallelAmount) {
        if (!holder.hasCapabilityProxies()) return 0;
        // Find all the fluids in the combined Fluid Input inventories and create oversized FluidStacks
        if (ConfigHolder.INSTANCE.dev.parallelSwitch) {
            List<Object2IntOpenHashMap<FluidStack>> inventoryGroups = getInventoryGroups(holder);
            if (inventoryGroups.isEmpty()) return 0;

            var ncMap = new Object2IntOpenHashMap<FluidIngredient>();
            var consumableMap = new Object2IntOpenHashMap<FluidIngredient>();

            for (Content content : recipe.getInputContents(FluidRecipeCapability.CAP)) {
                FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.content);
                (content.chance == 0 ? ncMap : consumableMap).addTo(ingredient, ingredient.getAmount());
            }

            if (consumableMap.isEmpty() && ncMap.isEmpty()) return parallelAmount;

            int maxMultiplier = 0;
            for (var group : inventoryGroups) {
                boolean satisfied = true;
                for (var it = ncMap.object2IntEntrySet().fastIterator(); it.hasNext(); ) {
                    var inputEntry = it.next();
                    FluidIngredient ingredient = inputEntry.getKey();
                    final int needed = inputEntry.getIntValue();
                    int available = 0;
                    for (var stackIter = group.object2IntEntrySet().fastIterator(); stackIter.hasNext(); ) {
                        var stackEntry = stackIter.next();
                        if (ingredient.test(stackEntry.getKey())) {
                            available += stackEntry.getIntValue();
                            if (available >= needed) break;
                        }
                    }
                    if (available < needed) {
                        satisfied = false;
                        break;
                    }
                }
                // Not enough NC -> skip this inventory
                if (!satisfied) continue;
                // Satisfied NC + no consumables -> early return
                if (consumableMap.isEmpty()) return parallelAmount;

                int invMultiplier = Integer.MAX_VALUE;
                // Loop over all consumables
                for (var it = consumableMap.object2IntEntrySet().fastIterator(); it.hasNext(); ) {
                    var inputEntry = it.next();
                    FluidIngredient ingredient = inputEntry.getKey();
                    final int needed = inputEntry.getIntValue();
                    final int maxNeeded = needed * parallelAmount;
                    int available = 0;
                    // Search stacks in our inventory group, summing them up
                    for (var stackEntry : group.object2IntEntrySet()) {
                        if (ingredient.test(stackEntry.getKey())) {
                            available += stackEntry.getIntValue();
                            // We can stop if we already have enough for max parallel
                            if (available >= maxNeeded) break;
                        }
                    }
                    // ratio will equal 0 if available < needed
                    int ratio = Math.min(parallelAmount, available / needed);
                    invMultiplier = Math.min(invMultiplier, ratio);
                    // Not enough of this ingredient in this group -> skip inventory
                    if (ratio == 0) break;
                }
                // We found an inventory group that can do max parallel -> early return
                if (invMultiplier == parallelAmount) return parallelAmount;
                maxMultiplier = Math.max(maxMultiplier, invMultiplier);
            }

            return maxMultiplier;
        }


        Map<FluidStack, Integer> fluidStacks = holder.getCapabilitiesFlat(IO.IN, FluidRecipeCapability.CAP).stream()
                .map(container -> container.getContents().stream().filter(FluidStack.class::isInstance)
                        .map(FluidStack.class::cast).toList())
                .flatMap(container -> GTHashMaps.fromFluidCollection(container).entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Integer::sum,
                        Object2IntLinkedOpenHashMap::new));

        int minMultiplier = Integer.MAX_VALUE;
        // map the recipe input fluids to account for duplicated fluids,
        // so their sum is counted against the total of fluids available in the input
        Map<FluidIngredient, Integer> fluidCountMap = new HashMap<>();
        Map<FluidIngredient, Integer> notConsumableMap = new HashMap<>();
        for (Content content : recipe.getInputContents(FluidRecipeCapability.CAP)) {
            FluidIngredient fluidInput = FluidRecipeCapability.CAP.of(content.content);
            int fluidAmount = fluidInput.getAmount();
            if (content.chance == 0) {
                notConsumableMap.computeIfPresent(fluidInput,
                        (k, v) -> v + fluidAmount);
                notConsumableMap.putIfAbsent(fluidInput, fluidAmount);
            } else {
                fluidCountMap.computeIfPresent(fluidInput,
                        (k, v) -> v + fluidAmount);
                fluidCountMap.putIfAbsent(fluidInput, fluidAmount);
            }
        }

        // Iterate through the recipe inputs, excluding the not consumable fluids from the fluid inventory map
        for (Map.Entry<FluidIngredient, Integer> notConsumableFluid : notConsumableMap.entrySet()) {
            int needed = notConsumableFluid.getValue();
            int available = 0;
            // For every fluid gathered from the fluid inputs.
            for (Map.Entry<FluidStack, Integer> inputFluid : fluidStacks.entrySet()) {
                // Strip the Non-consumable tags here, as FluidKey compares the tags, which causes finding matching
                // fluids
                // in the input tanks to fail, because there is nothing in those hatches with a non-consumable tag
                if (notConsumableFluid.getKey().test(inputFluid.getKey())) {
                    available = inputFluid.getValue();
                    if (available > needed) {
                        inputFluid.setValue(available - needed);
                        needed -= available;
                        break;
                    } else {
                        inputFluid.setValue(0);
                        notConsumableFluid.setValue(needed - available);
                        needed -= available;
                    }
                }
            }
            // We need to check >= available here because of Non-Consumable inputs with stack size. If there is a NC
            // input
            // with size 1000, and only 500 in the input, needed will be equal to available, but this situation should
            // still fail
            // as not all inputs are present
            if (needed >= available) {
                return 0;
            }
        }

        // Return the maximum parallel limit here if there are only non-consumed inputs, which are all found in the
        // input bus
        // At this point, we would have already returned 0 if we were missing any non-consumable inputs, so we can omit
        // that check
        if (fluidCountMap.isEmpty() && !notConsumableMap.isEmpty()) {
            return parallelAmount;
        }

        // Iterate through the fluid inputs in the recipe
        for (Map.Entry<FluidIngredient, Integer> fs : fluidCountMap.entrySet()) {
            int needed = fs.getValue();
            int available = 0;
            // For every fluid gathered from the fluid inputs.
            for (Map.Entry<FluidStack, Integer> inputFluid : fluidStacks.entrySet()) {
                if (fs.getKey().test(inputFluid.getKey())) {
                    available += inputFluid.getValue();
                }
            }
            if (available >= needed) {
                int ratio = (int) Math.min(parallelAmount, (float) available / needed);
                if (ratio < minMultiplier) {
                    minMultiplier = ratio;
                }
            } else {
                return 0;
            }
        }
        return minMultiplier;
    }

    private static List<Object2IntOpenHashMap<FluidStack>> getInventoryGroups(IRecipeCapabilityHolder holder) {
        var handlerLists = holder.getCapabilitiesForIO(IO.IN);
        if (handlerLists.isEmpty()) return Collections.emptyList();
        List<RecipeHandlerList> distinct = new ArrayList<>();
        List<IRecipeHandler<?>> indistinct = new ArrayList<>();

        for (var handlerList : handlerLists) {
            if (handlerList.isDistinct() && handlerList.hasCapability(FluidRecipeCapability.CAP)) {
                distinct.add(handlerList);
            } else if(handlerList.hasCapability(FluidRecipeCapability.CAP)) {
                indistinct.addAll(handlerList.getCapability(FluidRecipeCapability.CAP));
            }
        }
        List<Object2IntOpenHashMap<FluidStack>> invs = new ArrayList<>(distinct.size() + 1);
        Object2IntOpenHashMap<FluidStack> combined = new Object2IntOpenHashMap<>();
        for (var handler : indistinct) {
            if (!handler.shouldSearchContent()) continue;
            for (var content : handler.getContents()) {
                if (content instanceof FluidStack stack && !stack.isEmpty()) {
                    combined.addTo(stack, stack.getAmount());
                }
            }
        }

        for (var handlerList : distinct) {
            var handlers = handlerList.getCapability(ItemRecipeCapability.CAP);
            Object2IntOpenHashMap<FluidStack> inventory = new Object2IntOpenHashMap<>(combined);
            for (var handler : handlers) {
                if (!handler.shouldSearchContent()) continue;
                for (var content : handler.getContents()) {
                    if (content instanceof FluidStack stack && !stack.isEmpty()) {
                        inventory.addTo(stack, stack.getAmount());
                    }
                }
            }
            invs.add(inventory);
        }

        if (!combined.isEmpty()) invs.add(combined);
        return invs;
    }

    @Override
    public @NotNull List<Object> createXEIContainerContents(List<Content> contents, GTRecipe recipe, IO io) {
        return contents.stream().map(content -> content.content)
                .map(this::of)
                .map(FluidRecipeCapability::mapFluid)
                .collect(Collectors.toList());
    }

    public Object createXEIContainer(List<?> contents) {
        // cast is safe if you don't pass the wrong thing.
        // noinspection unchecked
        return new CycleFluidEntryHandler((List<FluidEntryList>) contents);
    }

    @NotNull
    @Override
    public Widget createWidget() {
        TankWidget tank = new TankWidget();
        tank.initTemplate();
        tank.setFillDirection(ProgressTexture.FillDirection.ALWAYS_FULL);
        return tank;
    }

    @NotNull
    @Override
    public Class<? extends Widget> getWidgetClass() {
        return TankWidget.class;
    }

    @Override
    public void applyWidgetInfo(@NotNull Widget widget,
                                int index,
                                boolean isXEI,
                                IO io,
                                GTRecipeTypeUI.@UnknownNullability("null when storage == null") RecipeHolder recipeHolder,
                                @NotNull GTRecipeType recipeType,
                                @UnknownNullability("null when content == null") GTRecipe recipe,
                                @Nullable Content content,
                                @Nullable Object storage, int recipeTier, int chanceTier) {
        if (widget instanceof TankWidget tank) {
            if (storage instanceof CycleFluidEntryHandler cycleHandler) {
                tank.setFluidTank(cycleHandler, index);
            } else if (storage instanceof IFluidHandlerModifiable fluidHandler) {
                tank.setFluidTank(new OverlayingFluidStorage(fluidHandler, index));
            }
            tank.setIngredientIO(io == IO.IN ? IngredientIO.INPUT : IngredientIO.OUTPUT);
            tank.setAllowClickFilled(!isXEI);
            tank.setAllowClickDrained(!isXEI && io.support(IO.IN));
            if (isXEI) tank.setShowAmount(false);
            if (content != null) {
                float chance = (float) recipeType.getChanceFunction()
                        .getBoostedChance(content, recipeTier, chanceTier) / content.maxChance;
                tank.setXEIChance(chance);
                tank.setOnAddedTooltips((w, tooltips) -> {
                    FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.content);
                    if (!isXEI && ingredient.getStacks().length > 0) {
                        FluidStack stack = ingredient.getStacks()[0];
                        TooltipsHandler.appendFluidTooltips(stack, tooltips::add, TooltipFlag.NORMAL);
                    }

                    GTRecipeWidget.setConsumedChance(content,
                            recipe.getChanceLogicForCapability(this, io, isTickSlot(index, io, recipe)),
                            tooltips, recipeTier, chanceTier, recipeType.getChanceFunction());
                    if (isTickSlot(index, io, recipe)) {
                        tooltips.add(Component.translatable("gtceu.gui.content.per_tick"));
                    }
                });
                if (io == IO.IN && (content.chance == 0)) {
                    tank.setIngredientIO(IngredientIO.CATALYST);
                }
            }
        }
    }

    // Maps fluids to a FluidEntryList for XEI: either a FluidTagList or a FluidStackList
    public static FluidEntryList mapFluid(FluidIngredient ingredient) {
        int amount = ingredient.getAmount();
        CompoundTag tag = ingredient.getNbt();

        FluidTagList tags = new FluidTagList();
        FluidStackList fluids = new FluidStackList();
        for (FluidIngredient.Value value : ingredient.values) {
            if (value instanceof FluidIngredient.TagValue tagValue) {
                tags.add(tagValue.getTag(), amount, ingredient.getNbt());
            } else {
                fluids.addAll(value.getFluids().stream().map(fluid -> new FluidStack(fluid, amount, tag)).toList());
            }
        }
        if (!tags.isEmpty()) {
            return tags;
        } else {
            return fluids;
        }
    }

    @Override
    public Object2IntMap<FluidIngredient> makeChanceCache() {
        return super.makeChanceCache();
    }

    public interface ICustomParallel {

        /**
         * Custom impl of the parallel limiter used by ParallelLogic to limit by outputs
         * 
         * @param recipe     Recipe
         * @param multiplier Initial multiplier
         * @return Limited multiplier
         */
        int limitParallel(GTRecipe recipe, int multiplier);
    }
}
