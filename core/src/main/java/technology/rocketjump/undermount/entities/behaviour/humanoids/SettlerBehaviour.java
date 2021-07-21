package technology.rocketjump.undermount.entities.behaviour.humanoids;

import com.alibaba.fastjson.JSONObject;
import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.math.GridPoint2;
import org.apache.commons.lang3.NotImplementedException;
import technology.rocketjump.undermount.entities.ai.goap.*;
import technology.rocketjump.undermount.entities.ai.memory.MemoryType;
import technology.rocketjump.undermount.entities.behaviour.AttachedLightSourceBehaviour;
import technology.rocketjump.undermount.entities.components.*;
import technology.rocketjump.undermount.entities.components.humanoid.*;
import technology.rocketjump.undermount.entities.model.Entity;
import technology.rocketjump.undermount.entities.model.physical.humanoid.Consciousness;
import technology.rocketjump.undermount.entities.model.physical.humanoid.HaulingComponent;
import technology.rocketjump.undermount.entities.model.physical.humanoid.HumanoidEntityAttributes;
import technology.rocketjump.undermount.entities.model.physical.humanoid.Sanity;
import technology.rocketjump.undermount.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.undermount.gamecontext.GameContext;
import technology.rocketjump.undermount.mapping.tile.CompassDirection;
import technology.rocketjump.undermount.mapping.tile.MapTile;
import technology.rocketjump.undermount.messaging.MessageType;
import technology.rocketjump.undermount.messaging.types.RequestLiquidAllocationMessage;
import technology.rocketjump.undermount.misc.Destructible;
import technology.rocketjump.undermount.persistence.SavedGameDependentDictionaries;
import technology.rocketjump.undermount.persistence.model.InvalidSaveException;
import technology.rocketjump.undermount.persistence.model.SavedGameStateHolder;
import technology.rocketjump.undermount.rooms.HaulingAllocation;
import technology.rocketjump.undermount.rooms.RoomStore;

import java.util.List;
import java.util.Optional;

import static technology.rocketjump.undermount.entities.ItemEntityMessageHandler.findStockpileAllocation;
import static technology.rocketjump.undermount.entities.ai.goap.SpecialGoal.*;
import static technology.rocketjump.undermount.entities.components.ItemAllocation.Purpose.DUE_TO_BE_HAULED;
import static technology.rocketjump.undermount.entities.components.ItemAllocation.Purpose.HELD_IN_INVENTORY;
import static technology.rocketjump.undermount.entities.components.humanoid.HappinessComponent.HappinessModifier.SAW_DEAD_BODY;
import static technology.rocketjump.undermount.entities.components.humanoid.HappinessComponent.MIN_HAPPINESS_VALUE;
import static technology.rocketjump.undermount.entities.model.EntityType.HUMANOID;
import static technology.rocketjump.undermount.entities.model.EntityType.ITEM;
import static technology.rocketjump.undermount.entities.model.physical.humanoid.Consciousness.AWAKE;
import static technology.rocketjump.undermount.entities.model.physical.humanoid.Consciousness.DEAD;
import static technology.rocketjump.undermount.misc.VectorUtils.toGridPoint;
import static technology.rocketjump.undermount.misc.VectorUtils.toVector;

public class SettlerBehaviour implements BehaviourComponent, Destructible, RequestLiquidAllocationMessage.LiquidAllocationCallback {

	private static final float MAX_DISTANCE_TO_DOUSE_FIRE = 12f;
	private static final float AMOUNT_REQUIRED_TO_DOUSE_FIRE = 0.5f;

	protected MessageDispatcher messageDispatcher;
	protected Entity parentEntity;
	protected GoalDictionary goalDictionary;
	protected RoomStore roomStore;

	protected Schedule schedule;
	protected AssignedGoal currentGoal;
	protected final GoalQueue goalQueue = new GoalQueue();
	protected SteeringComponent steeringComponent = new SteeringComponent();
	protected transient double lastUpdateGameTime;
	private static final int DISTANCE_TO_LOOK_AROUND = 5;

	public SettlerBehaviour() {

	}

	public SettlerBehaviour(GoalDictionary goalDictionary, ScheduleDictionary scheduleDictionary, RoomStore roomStore) {
		this.goalDictionary = goalDictionary;
		this.schedule = scheduleDictionary.settlerSchedule;
		this.roomStore = roomStore;
	}

	@Override
	public void init(Entity parentEntity, MessageDispatcher messageDispatcher, GameContext gameContext) {
		this.lastUpdateGameTime = gameContext.getGameClock().getCurrentGameTime();
		this.messageDispatcher = messageDispatcher;
		this.parentEntity = parentEntity;
		steeringComponent.init(parentEntity, gameContext.getAreaMap(), parentEntity.getLocationComponent(), messageDispatcher);

		if (currentGoal != null) {
			currentGoal.init(parentEntity, messageDispatcher);
		}
	}

	@Override
	public void destroy(Entity parentEntity, MessageDispatcher messageDispatcher, GameContext gameContext) {
		if (currentGoal != null) {
			currentGoal.destroy(parentEntity, messageDispatcher, gameContext);
			currentGoal = null;
		}
	}

	@Override
	public void update(float deltaTime, GameContext gameContext) {
		if (currentGoal == null || currentGoal.isComplete()) {
			currentGoal = pickNextGoalFromQueue(gameContext);
		}

		// Not going to update steering when asleep so can't be pushed around
		Consciousness consciousness = ((HumanoidEntityAttributes) parentEntity.getPhysicalEntityComponent().getAttributes()).getConsciousness();
		if (AWAKE.equals(consciousness)) {
			steeringComponent.update(deltaTime);
		}

		try {
			currentGoal.update(deltaTime, gameContext);
		} catch (SwitchGoalException e) {
			AssignedGoal newGoal = new AssignedGoal(e.target, parentEntity, messageDispatcher);
			newGoal.setAssignedJob(currentGoal.getAssignedJob());
			newGoal.setAssignedHaulingAllocation(currentGoal.getAssignedHaulingAllocation());
			newGoal.setLiquidAllocation(currentGoal.getLiquidAllocation());
			if (newGoal.getAssignedHaulingAllocation() == null) {
				newGoal.setAssignedHaulingAllocation(currentGoal.getAssignedJob().getHaulingAllocation());
			}
			currentGoal = newGoal;
		}
	}

	public AssignedGoal getCurrentGoal() {
		return currentGoal;
	}

	protected AssignedGoal pickNextGoalFromQueue(GameContext gameContext) {
		if (parentEntity.isOnFire()) {
			return onFireGoal(gameContext);
		}

		// (Override) if we're hauling an item, need to place it
		if (parentEntity.getComponent(HaulingComponent.class) != null) {
			Entity hauledEntity = parentEntity.getComponent(HaulingComponent.class).getHauledEntity();
			if (hauledEntity != null) {
				// need somewhere to place it

				HaulingAllocation stockpileAllocation = null;
				if (hauledEntity.getType().equals(ITEM)) {
					// Special case - if recently attempted to place item and failed, just dump it instead
					boolean recentlyFailedPlaceItemGoal = parentEntity.getOrCreateComponent(MemoryComponent.class)
							.getShortTermMemories(gameContext.getGameClock())
							.stream()
							.anyMatch(m -> m.getType().equals(MemoryType.FAILED_GOAL) && PLACE_ITEM.goalName.equals(m.getRelatedGoalName()));

					if (!recentlyFailedPlaceItemGoal) {
						// Temp un-requestAllocation
						ItemAllocationComponent itemAllocationComponent = hauledEntity.getOrCreateComponent(ItemAllocationComponent.class);
						itemAllocationComponent.cancelAll(ItemAllocation.Purpose.HAULING);

						stockpileAllocation = findStockpileAllocation(gameContext.getAreaMap(), hauledEntity, roomStore, parentEntity);

						if (stockpileAllocation != null && stockpileAllocation.getItemAllocation() != null) {
							// Stockpile allocation found, swap from DUE_TO_BE_HAULED
							ItemAllocation newAllocation = itemAllocationComponent.swapAllocationPurpose(DUE_TO_BE_HAULED, ItemAllocation.Purpose.HAULING, stockpileAllocation.getItemAllocation().getAllocationAmount());
							stockpileAllocation.setItemAllocation(newAllocation);
						}

						// Always re-allocate remaining amount to hauling
						if (itemAllocationComponent.getNumUnallocated() > 0) {
							itemAllocationComponent.createAllocation(itemAllocationComponent.getNumUnallocated(), parentEntity, ItemAllocation.Purpose.HAULING);
						}
					}
				}

				if (stockpileAllocation == null) {
					// Couldn't find any stockpile, just go somewhere nearby and dump
					return new AssignedGoal(DUMP_ITEM.getInstance(), parentEntity, messageDispatcher);
				} else {
					AssignedGoal assignedGoal = new AssignedGoal(PLACE_ITEM.getInstance(), parentEntity, messageDispatcher);
					assignedGoal.setAssignedHaulingAllocation(stockpileAllocation);
					return assignedGoal;
				}
			}
		}

		AssignedGoal placeInventoryItemsGoal = checkToPlaceInventoryItems(gameContext);
		if (placeInventoryItemsGoal != null) {
			return placeInventoryItemsGoal;
		}

		List<ScheduleCategory> currentScheduleCategories = schedule.getCurrentApplicableCategories(gameContext.getGameClock());
		QueuedGoal nextGoal = goalQueue.popNextGoal(currentScheduleCategories);
		if (nextGoal == null) {
			return new AssignedGoal(IDLE.getInstance(), parentEntity, messageDispatcher);
		}
		return new AssignedGoal(nextGoal.getGoal(), parentEntity, messageDispatcher);
	}

	private Optional<LiquidAllocation> liquidAllocation = Optional.empty();

	private AssignedGoal onFireGoal(GameContext gameContext) {
		liquidAllocation = Optional.empty();
		messageDispatcher.dispatchMessage(MessageType.REQUEST_LIQUID_ALLOCATION, new RequestLiquidAllocationMessage(
				parentEntity, AMOUNT_REQUIRED_TO_DOUSE_FIRE, false, true, this));

		if (liquidAllocation.isPresent()) {
			GridPoint2 accessLocation = liquidAllocation.get().getTargetZoneTile().getAccessLocation();
			float distanceToLiquidAllocation = parentEntity.getLocationComponent().getWorldOrParentPosition().dst(toVector(accessLocation));
			if (distanceToLiquidAllocation > MAX_DISTANCE_TO_DOUSE_FIRE) {
				messageDispatcher.dispatchMessage(MessageType.LIQUID_ALLOCATION_CANCELLED, liquidAllocation.get());
				liquidAllocation = Optional.empty();
			} else {
				AssignedGoal douseSelfGoal = new AssignedGoal(DOUSE_SELF.getInstance(), parentEntity, messageDispatcher);
				// return douse goal with allocation set
				douseSelfGoal.setLiquidAllocation(liquidAllocation.get());
				return douseSelfGoal;
			}
		}

		if (gameContext.getRandom().nextBoolean()) {
			return new AssignedGoal(ROLL_ON_FLOOR.getInstance(), parentEntity, messageDispatcher);
		}
		return new AssignedGoal(IDLE.getInstance(), parentEntity, messageDispatcher);
	}

	@Override
	public void allocationFound(Optional<LiquidAllocation> liquidAllocation) {
		this.liquidAllocation = liquidAllocation;
	}

	private AssignedGoal checkToPlaceInventoryItems(GameContext gameContext) {
		// Place an unused item into a stockpile if a space is available
		InventoryComponent inventory = parentEntity.getComponent(InventoryComponent.class);
		double currentGameTime = gameContext.getGameClock().getCurrentGameTime();
		for (InventoryComponent.InventoryEntry entry : inventory.getInventoryEntries()) {
			if (entry.entity.getType().equals(ITEM)) {
				ItemEntityAttributes attributes = (ItemEntityAttributes) entry.entity.getPhysicalEntityComponent().getAttributes();
				if (entry.getLastUpdateGameTime() + attributes.getItemType().getHoursInInventoryUntilUnused() < currentGameTime) {
					// Temp un-requestAllocation
					ItemAllocationComponent itemAllocationComponent = entry.entity.getOrCreateComponent(ItemAllocationComponent.class);
					itemAllocationComponent.cancelAll(HELD_IN_INVENTORY);

					HaulingAllocation stockpileAllocation = findStockpileAllocation(gameContext.getAreaMap(), entry.entity, roomStore, parentEntity);

					if (stockpileAllocation == null) {
						itemAllocationComponent.createAllocation(attributes.getQuantity(), parentEntity, HELD_IN_INVENTORY);
					} else {
						ItemAllocation newAllocation = itemAllocationComponent.swapAllocationPurpose(DUE_TO_BE_HAULED, HELD_IN_INVENTORY, stockpileAllocation.getItemAllocation().getAllocationAmount());
						stockpileAllocation.setItemAllocation(newAllocation);

						if (itemAllocationComponent.getNumUnallocated() > 0) {
							itemAllocationComponent.createAllocation(itemAllocationComponent.getNumUnallocated(), parentEntity, HELD_IN_INVENTORY);
						}

						return placeItemIntoStockpileGoal(entry.entity, stockpileAllocation);
					}
				}
			}
		}

		return null;
	}


	private AssignedGoal placeItemIntoStockpileGoal(Entity itemEntity, HaulingAllocation stockpileAllocation) {
		AssignedGoal assignedGoal = new AssignedGoal(PLACE_ITEM.getInstance(), parentEntity, messageDispatcher);
		assignedGoal.setAssignedHaulingAllocation(stockpileAllocation);
		ItemEntityAttributes attributes = (ItemEntityAttributes) itemEntity.getPhysicalEntityComponent().getAttributes();
		if (attributes.getItemType().isEquippedWhileWorkingOnJob()) {
			// Switch to hauling component
			HaulingComponent haulingComponent = parentEntity.getOrCreateComponent(HaulingComponent.class);
			InventoryComponent inventoryComponent = parentEntity.getComponent(InventoryComponent.class);
			inventoryComponent.remove(itemEntity.getId());
			haulingComponent.setHauledEntity(itemEntity, messageDispatcher, parentEntity);
		}
		return assignedGoal;
	}

	@Override
	public void infrequentUpdate(GameContext gameContext) {
		AttachedLightSourceBehaviour.infrequentUpdate(gameContext, parentEntity);

		double gameTime = gameContext.getGameClock().getCurrentGameTime();
		double elapsed = gameTime - lastUpdateGameTime;
		lastUpdateGameTime = gameTime;

		NeedsComponent needsComponent = parentEntity.getComponent(NeedsComponent.class);
		needsComponent.update(elapsed, parentEntity, messageDispatcher);

		parentEntity.getOrCreateComponent(StatusComponent.class).infrequentUpdate(elapsed);

		HappinessComponent happinessComponent = parentEntity.getOrCreateComponent(HappinessComponent.class);
		happinessComponent.infrequentUpdate(elapsed);
		HumanoidEntityAttributes attributes = (HumanoidEntityAttributes) parentEntity.getPhysicalEntityComponent().getAttributes();

		addGoalsToQueue(gameContext);

		lookAtNearbyThings(gameContext);

		if (attributes.getSanity().equals(Sanity.SANE) && attributes.getConsciousness().equals(AWAKE) &&
				happinessComponent.getNetModifier() <= MIN_HAPPINESS_VALUE) {
			messageDispatcher.dispatchMessage(MessageType.HUMANOID_INSANITY, parentEntity);
		}
	}

	public void setCurrentGoal(AssignedGoal assignedGoal) {
		this.currentGoal = assignedGoal;
	}

	public GoalQueue getGoalQueue() {
		return goalQueue;
	}

	protected void addGoalsToQueue(GameContext gameContext) {
		NeedsComponent needsComponent = parentEntity.getComponent(NeedsComponent.class);
		MemoryComponent memoryComponent = parentEntity.getComponent(MemoryComponent.class);
		goalQueue.removeExpiredGoals(gameContext.getGameClock());
		for (Goal potentialGoal : goalDictionary.getAllGoals()) {
			if (potentialGoal.getSelectors().isEmpty()) {
				continue; // Don't add goals with no selectors
			}
			if (currentGoal != null && potentialGoal.equals(currentGoal.goal)) {
				continue; // Don't queue up the current goal
			}
			for (GoalSelector selector : potentialGoal.getSelectors()) {
				boolean allConditionsApply = true;
				for (GoalSelectionCondition condition : selector.conditions) {
					if (!condition.apply(gameContext.getGameClock(), needsComponent, memoryComponent)) {
						allConditionsApply = false;
						break;
					}
				}
				if (allConditionsApply) {
					goalQueue.add(new QueuedGoal(potentialGoal, selector.scheduleCategory, selector.priority, gameContext.getGameClock()));
					break;
				}
			}
		}
	}

	private void lookAtNearbyThings(GameContext gameContext) {
		HumanoidEntityAttributes parentAttributes = (HumanoidEntityAttributes) parentEntity.getPhysicalEntityComponent().getAttributes();
		if (!parentAttributes.getConsciousness().equals(AWAKE)) {
			return;
		}

		HappinessComponent happinessComponent = parentEntity.getComponent(HappinessComponent.class);

		GridPoint2 parentPosition = toGridPoint(parentEntity.getLocationComponent().getWorldOrParentPosition());
		for (CompassDirection compassDirection : CompassDirection.values()) {
			for (int distance = 1; distance <= DISTANCE_TO_LOOK_AROUND; distance++) {
				GridPoint2 targetPosition = parentPosition.cpy().add(compassDirection.getXOffset() * distance, compassDirection.getYOffset() * distance);
				MapTile targetTile = gameContext.getAreaMap().getTile(targetPosition);
				if (targetTile == null || targetTile.hasWall()) {
					// Stop looking in this direction
					break;
				}

				for (Entity entityInTile : targetTile.getEntities()) {
					if (entityInTile.getType().equals(HUMANOID)) {
						HumanoidEntityAttributes humanoidEntityAttributes = (HumanoidEntityAttributes) entityInTile.getPhysicalEntityComponent().getAttributes();
						if (humanoidEntityAttributes.getConsciousness().equals(DEAD)) {
							// Saw a dead body!
							happinessComponent.add(SAW_DEAD_BODY);

							return; // TODO remove this, but for now this is the only thing to see so might as well stop looking
						}
					}
				}

			}
		}

	}

	@Override
	public SteeringComponent getSteeringComponent() {
		return steeringComponent;
	}

	@Override
	public boolean isUpdateEveryFrame() {
		return true;
	}

	@Override
	public boolean isUpdateInfrequently() {
		return true;
	}

	@Override
	public boolean isJobAssignable() {
		return true;
	}

	@Override
	public EntityComponent clone(MessageDispatcher messageDispatcher, GameContext gameContext) {
		throw new NotImplementedException("Not yet implemented " + this.getClass().getSimpleName() + ".clone()");
	}

	@Override
	public void writeTo(JSONObject asJson, SavedGameStateHolder savedGameStateHolder) {
		if (schedule != null) {
			asJson.put("schedule", schedule.getName());
		}

		if (currentGoal != null) {
			JSONObject currentGoalJson = new JSONObject(true);
			currentGoal.writeTo(currentGoalJson, savedGameStateHolder);
			asJson.put("currentGoal", currentGoalJson);
		}

		if (!goalQueue.isEmpty()) {
			JSONObject goalQueueJson = new JSONObject(true);
			goalQueue.writeTo(goalQueueJson, savedGameStateHolder);
			asJson.put("goalQueue", goalQueueJson);
		}

		if (steeringComponent != null) {
			JSONObject steeringComponentJson = new JSONObject(true);
			steeringComponent.writeTo(steeringComponentJson, savedGameStateHolder);
			asJson.put("steeringComponent", steeringComponentJson);
		}
	}

	@Override
	public void readFrom(JSONObject asJson, SavedGameStateHolder savedGameStateHolder, SavedGameDependentDictionaries relatedStores) throws InvalidSaveException {
		this.goalDictionary = relatedStores.goalDictionary;
		this.roomStore = relatedStores.roomStore;
		String scheduleName = asJson.getString("schedule");
		if (scheduleName != null) {
			this.schedule = relatedStores.scheduleDictionary.getByName(asJson.getString("schedule"));
			if (this.schedule == null) {
				throw new InvalidSaveException("Could not find schedule with name " + asJson.getString("schedule"));
			}
		}

		JSONObject currentGoalJson = asJson.getJSONObject("currentGoal");
		if (currentGoalJson != null) {
			currentGoal = new AssignedGoal();
			currentGoal.readFrom(currentGoalJson, savedGameStateHolder, relatedStores);
		}

		JSONObject goalQueueJson = asJson.getJSONObject("goalQueue");
		if (goalQueueJson != null) {
			goalQueue.readFrom(goalQueueJson, savedGameStateHolder, relatedStores);
		}

		JSONObject steeringComponentJson = asJson.getJSONObject("steeringComponent");
		if (steeringComponentJson != null) {
			this.steeringComponent.readFrom(steeringComponentJson, savedGameStateHolder, relatedStores);
		}
	}

}
