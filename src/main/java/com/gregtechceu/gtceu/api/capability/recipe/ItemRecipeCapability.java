package com.gregtechceu.gtceu.api.capability.recipe;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.gui.widget.SlotWidget;
import com.gregtechceu.gtceu.api.machine.trait.RecipeHandlerList;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.ResearchData;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gregtechceu.gtceu.api.recipe.content.SerializerIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.*;
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic;
import com.gregtechceu.gtceu.api.recipe.ui.GTRecipeTypeUI;
import com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition;
import com.gregtechceu.gtceu.common.valueprovider.AddedFloat;
import com.gregtechceu.gtceu.common.valueprovider.CastedFloat;
import com.gregtechceu.gtceu.common.valueprovider.FlooredInt;
import com.gregtechceu.gtceu.common.valueprovider.MultipliedFloat;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.core.mixins.IngredientAccessor;
import com.gregtechceu.gtceu.core.mixins.IntersectionIngredientAccessor;
import com.gregtechceu.gtceu.core.mixins.TagValueAccessor;
import com.gregtechceu.gtceu.integration.xei.entry.item.ItemEntryList;
import com.gregtechceu.gtceu.integration.xei.entry.item.ItemStackList;
import com.gregtechceu.gtceu.integration.xei.entry.item.ItemTagList;
import com.gregtechceu.gtceu.integration.xei.handlers.item.CycleItemEntryHandler;
import com.gregtechceu.gtceu.integration.xei.handlers.item.CycleItemStackHandler;
import com.gregtechceu.gtceu.integration.xei.widgets.GTRecipeWidget;
import com.gregtechceu.gtceu.utils.*;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.jei.IngredientIO;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.IntersectionIngredient;
import net.minecraftforge.common.crafting.PartialNBTIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * @author KilaBash
 * @date 2023/2/20
 * @implNote ItemRecipeCapability
 */
public class ItemRecipeCapability extends RecipeCapability<Ingredient> {

    public final static ItemRecipeCapability CAP = new ItemRecipeCapability();

    protected ItemRecipeCapability() {
        super("item", 0xFFD96106, true, 0, SerializerIngredient.INSTANCE);
    }

    @Override
    public Ingredient copyInner(Ingredient content) {
        return SizedIngredient.copy(content);
    }

    @Override
    public Ingredient copyWithModifier(Ingredient content, ContentModifier modifier) {
        if (content instanceof SizedIngredient sizedIngredient) {
            return SizedIngredient.create(sizedIngredient.getInner(),
                    modifier.apply(sizedIngredient.getAmount()));
        } else if (content instanceof IntProviderIngredient intProviderIngredient) {
            return new IntProviderIngredient(intProviderIngredient.getInner(),
                    new FlooredInt(
                            new AddedFloat(
                                    new MultipliedFloat(
                                            new CastedFloat(intProviderIngredient.getCountProvider()),
                                            ConstantFloat.of((float) modifier.multiplier())),
                                    ConstantFloat.of((float) modifier.addition()))));
        }
        return SizedIngredient.create(content, modifier.apply(1));
    }

    @Override
    public List<AbstractMapIngredient> convertToMapIngredient(Object obj) {
        List<AbstractMapIngredient> ingredients = new ObjectArrayList<>(1);
        if (obj instanceof Ingredient ingredient) {

            // all kinds of special cases
            if (ingredient instanceof StrictNBTIngredient nbt) {
                ingredients.addAll(MapItemStackNBTIngredient.from(nbt));
            } else if (ingredient instanceof PartialNBTIngredient nbt) {
                ingredients.addAll(MapItemStackPartialNBTIngredient.from(nbt));
            } else if (ingredient instanceof SizedIngredient sized) {
                if (sized.getInner() instanceof StrictNBTIngredient nbt) {
                    ingredients.addAll(MapItemStackNBTIngredient.from(nbt));
                } else if (sized.getInner() instanceof PartialNBTIngredient nbt) {
                    ingredients.addAll(MapItemStackPartialNBTIngredient.from(nbt));
                } else if (sized.getInner() instanceof IntersectionIngredient intersection) {
                    ingredients.add(new MapIntersectionIngredient(intersection));
                } else {
                    for (Ingredient.Value value : ((IngredientAccessor) sized.getInner()).getValues()) {
                        if (value instanceof Ingredient.TagValue tagValue) {
                            ingredients.add(new MapItemTagIngredient(((TagValueAccessor) tagValue).getTag()));
                        } else {
                            Collection<ItemStack> stacks = value.getItems();
                            for (ItemStack stack : stacks) {
                                ingredients.add(new MapItemStackIngredient(stack, sized.getInner()));
                            }
                        }
                    }
                }
            } else if (ingredient instanceof IntProviderIngredient intProvider) {
                if (intProvider.getInner() instanceof StrictNBTIngredient nbt) {
                    ingredients.addAll(MapItemStackNBTIngredient.from(nbt));
                } else if (intProvider.getInner() instanceof PartialNBTIngredient nbt) {
                    ingredients.addAll(MapItemStackPartialNBTIngredient.from(nbt));
                } else if (intProvider.getInner() instanceof IntersectionIngredient intersection) {
                    ingredients.add(new MapIntersectionIngredient(intersection));
                } else {
                    for (Ingredient.Value value : ((IngredientAccessor) intProvider.getInner()).getValues()) {
                        if (value instanceof Ingredient.TagValue tagValue) {
                            ingredients.add(new MapItemTagIngredient(((TagValueAccessor) tagValue).getTag()));
                        } else {
                            Collection<ItemStack> stacks = value.getItems();
                            for (ItemStack stack : stacks) {
                                ingredients.add(new MapItemStackIngredient(stack, intProvider.getInner()));
                            }
                        }
                    }
                }
            } else if (ingredient instanceof IntersectionIngredient intersection) {
                ingredients.add(new MapIntersectionIngredient(intersection));
            } else {
                for (Ingredient.Value value : ((IngredientAccessor) ingredient).getValues()) {
                    if (value instanceof Ingredient.TagValue tagValue) {
                        ingredients.add(new MapItemTagIngredient(((TagValueAccessor) tagValue).getTag()));
                    } else {
                        Collection<ItemStack> stacks = value.getItems();
                        for (ItemStack stack : stacks) {
                            ingredients.add(new MapItemStackIngredient(stack, ingredient));
                        }
                    }
                }
            }
        } else if (obj instanceof ItemStack stack) {
            ingredients.add(new MapItemStackIngredient(stack));

            stack.getTags().forEach(tag -> ingredients.add(new MapItemTagIngredient(tag)));
            if (stack.hasTag()) {
                ingredients.add(new MapItemStackNBTIngredient(stack, StrictNBTIngredient.of(stack)));
            }
            if (stack.getShareTag() != null) {
                ingredients.add(new MapItemStackPartialNBTIngredient(stack,
                        PartialNBTIngredient.of(stack.getItem(), stack.getShareTag())));
            }
            TagPrefix prefix = ChemicalHelper.getPrefix(stack.getItem());
            if (!prefix.isEmpty() && TagPrefix.ORES.containsKey(prefix)) {
                Material material = ChemicalHelper.getMaterialStack(stack.getItem()).material();
                ingredients.add(new MapIntersectionIngredient((IntersectionIngredient) IntersectionIngredient.of(
                        Ingredient.of(prefix.getItemTags(material)[0]), Ingredient.of(prefix.getItemParentTags()[0]))));
            }
        }
        return ingredients;
    }

    @Override
    public List<Object> compressIngredients(Collection<Object> ingredients) {
        List<Object> list = new ObjectArrayList<>(ingredients.size());
        for (Object item : ingredients) {
            if (item instanceof Ingredient ingredient) {
                boolean isEqual = false;
                for (Object obj : list) {
                    if (obj instanceof Ingredient ingredient1) {
                        if (IngredientEquality.ingredientEquals(ingredient, ingredient1)) {
                            isEqual = true;
                            break;
                        }
                    } else if (obj instanceof ItemStack stack) {
                        if (ingredient.test(stack)) {
                            isEqual = true;
                            break;
                        }
                    }
                }
                if (isEqual) continue;
                //@formatter:off
                if (ingredient instanceof IntCircuitIngredient) {
                    list.add(0, ingredient);
                } else if (ingredient instanceof SizedIngredient sized &&
                        sized.getInner() instanceof IntCircuitIngredient) {
                    list.add(0, ingredient);
                } else if (ingredient instanceof IntProviderIngredient intProvider &&
                        intProvider.getInner() instanceof IntCircuitIngredient) {
                    list.add(0, ingredient);
                } else {
                    list.add(ingredient);
                }
                //@formatter:on
            } else if (item instanceof ItemStack stack) {
                boolean isEqual = false;
                for (Object obj : list) {
                    if (obj instanceof Ingredient ingredient) {
                        if (ingredient.test(stack)) {
                            isEqual = true;
                            break;
                        }
                    } else if (obj instanceof ItemStack stack1) {
                        if (ItemStack.isSameItem(stack, stack1)) {
                            isEqual = true;
                            break;
                        }
                    }
                }
                if (isEqual) continue;
                list.add(stack);
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

        var handlers = holder.getCapabilitiesFlat(IO.OUT, ItemRecipeCapability.CAP);
        if (handlers.isEmpty()) return 0;

        var outputContents = recipe.getOutputContents(ItemRecipeCapability.CAP);
        if (outputContents.isEmpty()) return multiplier;

        int minMultiplier = 0;
        int maxMultiplier = multiplier;

        if (ConfigHolder.INSTANCE.dev.parallelSwitch) {
            int maxCount = 0;
            List<Ingredient> ingredients = new ArrayList<>(outputContents.size());
            for (var content : outputContents) {
                var ing = ItemRecipeCapability.CAP.of(content.content);
                int count = 1;
                if (ing instanceof SizedIngredient sizedIngredient) {
                    count = sizedIngredient.getAmount();
                } else if (ing instanceof IntProviderIngredient intProviderIngredient) {
                    count = intProviderIngredient.getSampledCount(GTValues.RNG);
                }
                maxCount = Math.max(maxCount, count);
                ingredients.add(ing);
            }

            if (maxCount != 0 && multiplier > Integer.MAX_VALUE / maxCount) multiplier = Integer.MAX_VALUE / maxCount;
            maxMultiplier = multiplier;

            while (minMultiplier != maxMultiplier) {
                List<Ingredient> copied = new ArrayList<>();
                for (final var ing : ingredients) {
                    copied.add(ItemRecipeCapability.CAP.copyWithModifier(ing, ContentModifier.multiplier(multiplier)));
                }
                for (var handler : handlers) {
                    // noinspection unchecked
                    copied = (List<Ingredient>) handler.handleRecipe(IO.OUT, recipe, copied, true);
                    if (copied == null) break;
                }
                int[] bin = ParallelLogic.adjustMultiplier(copied == null, minMultiplier, multiplier, maxMultiplier);
                minMultiplier = bin[0];
                multiplier = bin[1];
                maxMultiplier = bin[2];
            }
            
            return multiplier;
        }

        OverlayedItemHandler itemHandler = new OverlayedItemHandler(new CombinedInvWrapper(
                handlers.stream()
                        .filter(IItemHandlerModifiable.class::isInstance)
                        .map(IItemHandlerModifiable.class::cast)
                        .toArray(IItemHandlerModifiable[]::new)));

        Object2IntMap<ItemStack> recipeOutputs = GTHashMaps
                .fromItemStackCollection(outputContents
                        .stream()
                        .map(content -> ItemRecipeCapability.CAP.of(content.getContent()))
                        .filter(ingredient -> !ingredient.isEmpty())
                        .map(ingredient -> ingredient.getItems()[0])
                        .toList());

        while (minMultiplier != maxMultiplier) {
            itemHandler.reset();

            int returnedAmount = 0;
            int amountToInsert;

            for (Object2IntMap.Entry<ItemStack> entry : recipeOutputs.object2IntEntrySet()) {
                // Since multiplier starts at Int.MAX, check here for integer overflow
                if (entry.getIntValue() != 0 && multiplier > Integer.MAX_VALUE / entry.getIntValue()) {
                    amountToInsert = Integer.MAX_VALUE;
                } else {
                    amountToInsert = entry.getIntValue() * multiplier;
                }
                returnedAmount = itemHandler.insertStackedItemStack(entry.getKey(), amountToInsert);
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
        // Find all the items in the combined Item Input inventories and create oversized ItemStacks
        List<Object2IntMap<ItemStack>> inventoryGroups = getInventoryGroups(holder);
        if (inventoryGroups.isEmpty()) return 0;

        int minMultiplier = Integer.MAX_VALUE;
        // map the recipe ingredients to account for duplicated and notConsumable ingredients.
        // notConsumable ingredients are not counted towards the max ratio
        Object2IntOpenHashMap<Ingredient> notConsumableMap = new Object2IntOpenHashMap<>();
        Object2IntOpenHashMap<Ingredient> consumableMap = new Object2IntOpenHashMap<>();
        for (Content content : recipe.getInputContents(ItemRecipeCapability.CAP)) {
            Ingredient recipeIngredient = ItemRecipeCapability.CAP.of(content.content);
            int ingredientCount;
            if (recipeIngredient instanceof IntCircuitIngredient) continue;
            if (recipeIngredient instanceof SizedIngredient sizedIngredient) {
                ingredientCount = sizedIngredient.getAmount();
            } else if (recipeIngredient instanceof IntProviderIngredient intProviderIngredient) {
                ingredientCount = intProviderIngredient.getSampledCount(GTValues.RNG);
            } else {
                ingredientCount = 1;
            }
            if (content.chance == 0) {
                notConsumableMap.addTo(recipeIngredient, ingredientCount);
            } else {
                consumableMap.addTo(recipeIngredient, ingredientCount);
            }
        }

        // is this even possible
        if(consumableMap.isEmpty() && notConsumableMap.isEmpty()) return parallelAmount;

        int maxMultiplier = 0;
        // Check every inventory group
        for (var group : inventoryGroups) {
            // Check for enough NC in inventory group
            boolean satisfied = true;
            for (var it = notConsumableMap.object2IntEntrySet().fastIterator(); it.hasNext(); ) {
                var inputEntry = it.next();
                Ingredient ingredient = inputEntry.getKey();
                final int needed = inputEntry.getIntValue();
                int available = 0;
                for (var stackEntry : group.object2IntEntrySet()) {
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
            for (var inputEntry : consumableMap.object2IntEntrySet()) {
                Ingredient ingredient = inputEntry.getKey();
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

//        // Iterate through the recipe inputs, excluding the not consumable ingredients from the inventory map
//        for (Object2IntMap.Entry<Ingredient> recipeInputEntry : notConsumableMap.object2IntEntrySet()) {
//            int needed = recipeInputEntry.getIntValue();
//            int available = 0;
//            // For every stack in the ingredients gathered from the input bus.
//            for (Object2IntMap.Entry<ItemStack> inventoryEntry : ingredientStacks.object2IntEntrySet()) {
//                if (recipeInputEntry.getKey().test(inventoryEntry.getKey())) {
//                    available = inventoryEntry.getIntValue();
//                    if (available > needed) {
//                        inventoryEntry.setValue(available - needed);
//                        needed -= available;
//                        break;
//                    } else {
//                        inventoryEntry.setValue(0);
//                        recipeInputEntry.setValue(needed - available);
//                        needed -= available;
//                    }
//                }
//            }
//            // We need to check >= available here because of Non-Consumable inputs with stack size. If there is a NC
//            // input
//            // with size 2, and only 1 in the input, needed will be equal to available, but this situation should still
//            // fail
//            // as not all inputs are present
//            if (needed >= available) {
//                return 0;
//            }
//        }
//
//        // Return the maximum parallel limit here if there are only non-consumed inputs, which are all found in the
//        // input bus
//        // At this point, we would have already returned 0 if we were missing any non-consumable inputs, so we can omit
//        // that check
//        if (countableMap.isEmpty() && !notConsumableMap.isEmpty()) {
//            return parallelAmount;
//        }
//
//        // Iterate through the recipe inputs
//        for (Object2IntMap.Entry<Ingredient> recipeInputEntry : countableMap.object2IntEntrySet()) {
//            int needed = recipeInputEntry.getIntValue();
//            int available = 0;
//            // For every stack in the ingredients gathered from the input bus.
//            for (Object2IntMap.Entry<ItemStack> inventoryEntry : ingredientStacks.object2IntEntrySet()) {
//                if (recipeInputEntry.getKey().test(inventoryEntry.getKey())) {
//                    available += inventoryEntry.getIntValue();
//                    break;
//                }
//            }
//            if (available >= needed) {
//                int ratio = Math.min(parallelAmount, available / needed);
//                if (ratio < minMultiplier) {
//                    minMultiplier = ratio;
//                }
//            } else {
//                return 0;
//            }
//        }
//        return minMultiplier;
    }

    private static List<Object2IntMap<ItemStack>> getInventoryGroups(IRecipeCapabilityHolder holder) {
        var handlerLists = holder.getCapabilitiesForIO(IO.IN);
        if (handlerLists.isEmpty()) return Collections.emptyList();
        List<RecipeHandlerList> distinct = new ArrayList<>();
        List<IRecipeHandler<?>> indistinct = new ArrayList<>();

        for (var handlerList : handlerLists) {
            if (handlerList.isDistinct() && handlerList.hasCapability(ItemRecipeCapability.CAP)) {
                distinct.add(handlerList);
            } else if(handlerList.hasCapability(ItemRecipeCapability.CAP)) {
                indistinct.addAll(handlerList.getCapability(ItemRecipeCapability.CAP));
            }
        }

        final var strat = ItemStackHashStrategy.comparingAllButCount();

        List<Object2IntMap<ItemStack>> invs = new ArrayList<>(distinct.size() + 1);
        Object2IntOpenCustomHashMap<ItemStack> combined = new Object2IntOpenCustomHashMap<>(strat);

        for (var handler : indistinct) {
            if (!handler.shouldSearchContent()) continue;
            for (var content : handler.getContents()) {
                if (content instanceof ItemStack stack && !stack.isEmpty()) {
                    combined.addTo(stack, stack.getCount());
                }
            }
        }

        for (var handlerList : distinct) {
            var handlers = handlerList.getCapability(ItemRecipeCapability.CAP);
            Object2IntOpenCustomHashMap<ItemStack> inventory = new Object2IntOpenCustomHashMap<>(combined, strat);
            for (var handler : handlers) {
                if (!handler.shouldSearchContent()) continue;
                for (var content : handler.getContents()) {
                    if (content instanceof ItemStack stack && !stack.isEmpty()) {
                        inventory.addTo(stack, stack.getCount());
                    }
                }
            }
            invs.add(inventory);
        }

        if (!combined.isEmpty()) invs.add(combined);
        return invs;


//        var handlers = holder.getCapabilitiesFlat(IO.IN, ItemRecipeCapability.CAP);
//
//        List<Object2IntMap<ItemStack>> inventories = new ObjectArrayList<>();
//        Object2IntMap<ItemStack> combined = new Object2IntOpenCustomHashMap<>(ItemStackHashStrategy.comparingAllButCount());
//
//        for (IRecipeHandler<?> handler : handlers) {
//            Stream<ItemStack> stackStream = handler.getContents()
//                    .stream()
//                    .filter(ItemStack.class::isInstance)
//                    .map(ItemStack.class::cast);
//
//            if(handler.isDistinct()) {
//                var map = stackStream.collect(Collectors.toMap(
//                        Function.identity(),
//                        ItemStack::getCount,
//                        Integer::sum,
//                        () -> GTHashMaps.createItemStackMap(false))
//                );
//                if(!map.isEmpty()) inventories.add(map);
//            } else {
//                stackStream.forEach(stack -> combined.mergeInt(stack, stack.getCount(), Integer::sum));
//            }
//        }
//
//        if(!combined.isEmpty()) {
////            for (var inventory : inventories) {
////                combined.forEach((stack, count) -> inventory.mergeInt(stack, count, Integer::sum));
////            }
//            inventories.add(combined);
//        }
//        return inventories;
    }

    @Override
    public @NotNull List<Object> createXEIContainerContents(List<Content> contents, GTRecipe recipe, IO io) {
        var entryLists = contents.stream()
                .map(Content::getContent)
                .map(this::of)
                .map(ItemRecipeCapability::mapItem)
                .collect(Collectors.toList());

        List<ItemEntryList> scannerPossibilities = null;
        if (io == IO.OUT && recipe.recipeType.isScanner()) {
            scannerPossibilities = new ArrayList<>();
            // Scanner Output replacing, used for cycling research outputs
            Pair<GTRecipeType, String> researchData = null;
            for (Content stack : recipe.getOutputContents(ItemRecipeCapability.CAP)) {
                researchData = ResearchManager.readResearchId(ItemRecipeCapability.CAP.of(stack.content).getItems()[0]);
                if (researchData != null) break;
            }
            if (researchData != null) {
                Collection<GTRecipe> possibleRecipes = researchData.getFirst()
                        .getDataStickEntry(researchData.getSecond());
                Set<ItemStack> cache = new ObjectOpenCustomHashSet<>(ItemStackHashStrategy.comparingItem());
                if (possibleRecipes != null) {
                    for (GTRecipe r : possibleRecipes) {
                        Content outputContent = r.getOutputContents(ItemRecipeCapability.CAP).get(0);
                        ItemStack researchStack = ItemRecipeCapability.CAP.of(outputContent.content).getItems()[0];
                        if (!cache.contains(researchStack)) {
                            cache.add(researchStack);
                            scannerPossibilities.add(ItemStackList.of(researchStack.copyWithCount(1)));
                        }
                    }
                }
                scannerPossibilities.add(entryLists.get(0));
            }
        }

        if (scannerPossibilities != null && !scannerPossibilities.isEmpty()) {
            entryLists = scannerPossibilities;
        }
        while (entryLists.size() < recipe.recipeType.getMaxOutputs(ItemRecipeCapability.CAP)) entryLists.add(null);

        return new ArrayList<>(entryLists);
    }

    public Object createXEIContainer(List<?> contents) {
        // cast is safe if you don't pass the wrong thing.
        // noinspection unchecked
        return new CycleItemEntryHandler((List<ItemEntryList>) contents);
    }

    @NotNull
    @Override
    public Widget createWidget() {
        SlotWidget slot = new SlotWidget();
        slot.initTemplate();
        return slot;
    }

    @NotNull
    @Override
    public Class<? extends Widget> getWidgetClass() {
        return SlotWidget.class;
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
        if (widget instanceof SlotWidget slot) {
            if (storage instanceof IItemHandlerModifiable items) {
                if (index >= 0 && index < items.getSlots()) {
                    slot.setHandlerSlot(items, index);
                    slot.setIngredientIO(io == IO.IN ? IngredientIO.INPUT : IngredientIO.OUTPUT);
                    slot.setCanTakeItems(!isXEI);
                    slot.setCanPutItems(!isXEI && io.support(IO.IN));
                }
                // 1 over container size.
                // If in a recipe viewer and a research slot can be added, add it.
                if (isXEI && recipeType.isHasResearchSlot() && index == items.getSlots()) {
                    if (ConfigHolder.INSTANCE.machines.enableResearch) {
                        ResearchCondition condition = recipeHolder.conditions().stream()
                                .filter(ResearchCondition.class::isInstance).findAny()
                                .map(ResearchCondition.class::cast).orElse(null);
                        if (condition != null) {
                            List<ItemStack> dataItems = new ArrayList<>();
                            for (ResearchData.ResearchEntry entry : condition.data) {
                                ItemStack dataStick = entry.getDataItem().copy();
                                ResearchManager.writeResearchToNBT(dataStick.getOrCreateTag(), entry.getResearchId(),
                                        recipeType);
                                dataItems.add(dataStick);
                            }
                            CycleItemStackHandler handler = new CycleItemStackHandler(List.of(dataItems));
                            slot.setHandlerSlot(handler, 0);
                            slot.setIngredientIO(IngredientIO.CATALYST);
                            slot.setCanTakeItems(false);
                            slot.setCanPutItems(false);
                        }
                    }
                }
            }
            if (content != null) {
                float chance = (float) recipeType.getChanceFunction()
                        .getBoostedChance(content, recipeTier, chanceTier) / content.maxChance;
                slot.setXEIChance(chance);
                slot.setOnAddedTooltips((w, tooltips) -> {
                    GTRecipeWidget.setConsumedChance(content,
                            recipe.getChanceLogicForCapability(this, io, isTickSlot(index, io, recipe)),
                            tooltips, recipeTier, chanceTier, recipeType.getChanceFunction());
                    //@formatter:off
                    if (this.of(content.content) instanceof IntProviderIngredient ingredient) {
                        IntProvider countProvider = ingredient.getCountProvider();
                        tooltips.add(Component.translatable("gtceu.gui.content.count_range",
                                countProvider.getMinValue(), countProvider.getMaxValue())
                                .withStyle(ChatFormatting.GOLD));
                    } else if (this.of(content.content) instanceof SizedIngredient sizedIngredient &&
                            sizedIngredient.getInner() instanceof IntProviderIngredient ingredient) {
                        IntProvider countProvider = ingredient.getCountProvider();
                        tooltips.add(Component.translatable("gtceu.gui.content.count_range",
                                countProvider.getMinValue(), countProvider.getMaxValue())
                                .withStyle(ChatFormatting.GOLD));
                    }
                    //@formatter:on
                    if (isTickSlot(index, io, recipe)) {
                        tooltips.add(Component.translatable("gtceu.gui.content.per_tick"));
                    }
                });
                if (io == IO.IN && (content.chance == 0 || this.of(content.content) instanceof IntCircuitIngredient)) {
                    slot.setIngredientIO(IngredientIO.CATALYST);
                }
            }
        }
    }

    // Maps ingredients to an ItemEntryList for XEI: either an ItemTagList or an ItemStackList
    private static ItemEntryList mapItem(final Ingredient ingredient) {
        if (ingredient instanceof SizedIngredient sizedIngredient) {
            final int amount = sizedIngredient.getAmount();
            var mapped = tryMapInner(sizedIngredient.getInner(), amount);
            if (mapped != null) return mapped;
        } else if (ingredient instanceof IntProviderIngredient intProvider) {
            final int amount = 1;
            var mapped = tryMapInner(intProvider.getInner(), amount);
            if (mapped != null) return mapped;
        } else if (ingredient instanceof IntersectionIngredient intersection) {
            return mapIntersection(intersection, -1);
        } else {
            var tagList = tryMapTag(ingredient, 1);
            if (tagList != null) return tagList;
        }
        ItemStackList stackList = new ItemStackList();
        boolean isIntProvider = ingredient instanceof IntProviderIngredient ||
                (ingredient instanceof SizedIngredient sized && sized.getInner() instanceof IntProviderIngredient);

        UnaryOperator<ItemStack> setCount = stack -> isIntProvider ? stack.copyWithCount(1) : stack;
        Arrays.stream(ingredient.getItems())
                .map(setCount)
                .forEach(stackList::add);
        return stackList;
    }

    private static @Nullable ItemEntryList tryMapInner(final Ingredient inner, int amount) {
        if (inner instanceof IntersectionIngredient intersection) return mapIntersection(intersection, amount);
        return tryMapTag(inner, amount);
    }

    // Map intersection ingredients to the items inside, as recipe viewers don't support them.
    private static ItemEntryList mapIntersection(final IntersectionIngredient intersection, int amount) {
        List<Ingredient> children = ((IntersectionIngredientAccessor) intersection).getChildren();
        if (children.isEmpty()) return new ItemStackList();

        var childList = mapItem(children.get(0));
        ItemStackList stackList = new ItemStackList();
        for (var stack : childList.getStacks()) {
            if (children.stream().skip(1).allMatch(child -> child.test(stack))) {
                if (amount > 0) stackList.add(stack.copyWithCount(amount));
                else stackList.add(stack.copy());
            }
        }
        return stackList;
    }

    private static @Nullable ItemTagList tryMapTag(final Ingredient ingredient, int amount) {
        var values = ((IngredientAccessor) ingredient).getValues();
        if (values.length > 0 && values[0] instanceof Ingredient.TagValue tagValue) {
            return ItemTagList.of(((TagValueAccessor) tagValue).getTag(), amount, null);
        }
        return null;
    }

    @Override
    public Object2IntMap<Ingredient> makeChanceCache() {
        return new Object2IntOpenCustomHashMap<>(IngredientEquality.IngredientHashStrategy.INSTANCE);
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
