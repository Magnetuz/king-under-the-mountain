package technology.rocketjump.undermount.jobs;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.ai.msg.Telegraph;
import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.math.Vector2;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.pmw.tinylog.Logger;
import technology.rocketjump.undermount.assets.model.FloorType;
import technology.rocketjump.undermount.cooking.model.CookingRecipe;
import technology.rocketjump.undermount.doors.Doorway;
import technology.rocketjump.undermount.entities.EntityStore;
import technology.rocketjump.undermount.entities.behaviour.furniture.CraftingStationBehaviour;
import technology.rocketjump.undermount.entities.behaviour.furniture.InnoculationLogBehaviour;
import technology.rocketjump.undermount.entities.behaviour.furniture.OnJobCompletion;
import technology.rocketjump.undermount.entities.behaviour.humanoids.SettlerBehaviour;
import technology.rocketjump.undermount.entities.behaviour.plants.FallingTreeBehaviour;
import technology.rocketjump.undermount.entities.components.BehaviourComponent;
import technology.rocketjump.undermount.entities.components.InventoryComponent;
import technology.rocketjump.undermount.entities.components.ItemAllocationComponent;
import technology.rocketjump.undermount.entities.components.LiquidContainerComponent;
import technology.rocketjump.undermount.entities.components.furniture.ConstructedEntityComponent;
import technology.rocketjump.undermount.entities.components.furniture.DecorationInventoryComponent;
import technology.rocketjump.undermount.entities.components.furniture.HarvestableEntityComponent;
import technology.rocketjump.undermount.entities.dictionaries.furniture.FurnitureTypeDictionary;
import technology.rocketjump.undermount.entities.factories.ItemEntityAttributesFactory;
import technology.rocketjump.undermount.entities.factories.ItemEntityFactory;
import technology.rocketjump.undermount.entities.factories.PlantEntityAttributesFactory;
import technology.rocketjump.undermount.entities.factories.PlantEntityFactory;
import technology.rocketjump.undermount.entities.model.Entity;
import technology.rocketjump.undermount.entities.model.EntityType;
import technology.rocketjump.undermount.entities.model.physical.furniture.FurnitureEntityAttributes;
import technology.rocketjump.undermount.entities.model.physical.furniture.FurnitureType;
import technology.rocketjump.undermount.entities.model.physical.humanoid.EquippedItemComponent;
import technology.rocketjump.undermount.entities.model.physical.humanoid.HaulingComponent;
import technology.rocketjump.undermount.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.undermount.entities.model.physical.item.ItemType;
import technology.rocketjump.undermount.entities.model.physical.item.ItemTypeDictionary;
import technology.rocketjump.undermount.entities.model.physical.plant.*;
import technology.rocketjump.undermount.entities.tags.DeceasedContainerTag;
import technology.rocketjump.undermount.entities.tags.ReplacementDeconstructionResourcesTag;
import technology.rocketjump.undermount.entities.tags.SupportsRoofTag;
import technology.rocketjump.undermount.gamecontext.GameContext;
import technology.rocketjump.undermount.gamecontext.GameContextAware;
import technology.rocketjump.undermount.jobs.model.Job;
import technology.rocketjump.undermount.jobs.model.JobState;
import technology.rocketjump.undermount.jobs.model.JobTarget;
import technology.rocketjump.undermount.jobs.model.JobType;
import technology.rocketjump.undermount.mapping.tile.MapTile;
import technology.rocketjump.undermount.mapping.tile.TileNeighbours;
import technology.rocketjump.undermount.mapping.tile.designation.TileDesignation;
import technology.rocketjump.undermount.mapping.tile.designation.TileDesignationDictionary;
import technology.rocketjump.undermount.mapping.tile.floor.BridgeTile;
import technology.rocketjump.undermount.mapping.tile.wall.Wall;
import technology.rocketjump.undermount.materials.DynamicMaterialFactory;
import technology.rocketjump.undermount.materials.model.GameMaterial;
import technology.rocketjump.undermount.materials.model.GameMaterialType;
import technology.rocketjump.undermount.messaging.MessageType;
import technology.rocketjump.undermount.messaging.types.*;
import technology.rocketjump.undermount.particles.ParticleEffectTypeDictionary;
import technology.rocketjump.undermount.particles.model.ParticleEffectType;
import technology.rocketjump.undermount.rooms.Bridge;
import technology.rocketjump.undermount.rooms.constructions.Construction;
import technology.rocketjump.undermount.ui.GameInteractionMode;
import technology.rocketjump.undermount.ui.GameInteractionStateContainer;

import java.util.*;

import static technology.rocketjump.undermount.entities.behaviour.furniture.InnoculationLogBehaviour.InnoculationLogState.INNOCULATING;
import static technology.rocketjump.undermount.entities.components.ItemAllocation.Purpose.HELD_IN_INVENTORY;
import static technology.rocketjump.undermount.entities.model.EntityType.*;
import static technology.rocketjump.undermount.materials.model.GameMaterial.NULL_MATERIAL;

/**
 * This class deals with dishing out jobs to entities requesting them
 */
@Singleton
public class JobMessageHandler implements GameContextAware, Telegraph {

	private final MessageDispatcher messageDispatcher;
	private final JobStore jobStore;
	private final ItemEntityFactory itemEntityFactory;
	private final ItemEntityAttributesFactory itemEntityAttributesFactory;
	private final JobFactory jobFactory;
	private final EntityStore entityStore;
	private final PlantEntityAttributesFactory plantEntityAttributesFactory;
	private final PlantEntityFactory plantEntityFactory;
	private final PlantSpeciesDictionary plantSpeciesDictionary;
	private final FurnitureTypeDictionary furnitureTypeDictionary;
	private final DynamicMaterialFactory dynamicMaterialFactory;
	private final ItemTypeDictionary itemTypeDictionary;
	private final JobType haulingJobType;
	private final JobType miningJobType;
	private final JobType constructFlooringJobType;
	private final TileDesignationDictionary tileDesignationDictionary;
	private final ParticleEffectType leafExplosionParticleEffectType;
	private final GameInteractionStateContainer gameInteractionStateContainer;
	private GameContext gameContext;
	private ParticleEffectType deconstructParticleEffect;

	@Inject
	public JobMessageHandler(MessageDispatcher messageDispatcher, JobStore jobStore,
							 ItemEntityFactory itemEntityFactory, ItemEntityAttributesFactory itemEntityAttributesFactory,
							 JobFactory jobFactory, EntityStore entityStore, PlantEntityAttributesFactory plantEntityAttributesFactory,
							 PlantEntityFactory plantEntityFactory, PlantSpeciesDictionary plantSpeciesDictionary,
							 FurnitureTypeDictionary furnitureTypeDictionary, DynamicMaterialFactory dynamicMaterialFactory,
							 ItemTypeDictionary itemTypeDictionary, JobTypeDictionary jobTypeDictionary,
							 TileDesignationDictionary tileDesignationDictionary, ParticleEffectTypeDictionary particleEffectTypeDictionary,
							 GameInteractionStateContainer gameInteractionStateContainer) {
		this.messageDispatcher = messageDispatcher;
		this.jobStore = jobStore;
		this.itemEntityFactory = itemEntityFactory;
		this.itemEntityAttributesFactory = itemEntityAttributesFactory;
		this.jobFactory = jobFactory;
		this.entityStore = entityStore;
		this.plantEntityAttributesFactory = plantEntityAttributesFactory;
		this.plantEntityFactory = plantEntityFactory;
		this.plantSpeciesDictionary = plantSpeciesDictionary;
		this.furnitureTypeDictionary = furnitureTypeDictionary;
		this.dynamicMaterialFactory = dynamicMaterialFactory;
		this.itemTypeDictionary = itemTypeDictionary;
		haulingJobType = jobTypeDictionary.getByName("HAULING");
		miningJobType = jobTypeDictionary.getByName("MINING");
		constructFlooringJobType = jobTypeDictionary.getByName("CONSTRUCT_FLOORING");
		this.tileDesignationDictionary = tileDesignationDictionary;

		this.leafExplosionParticleEffectType = particleEffectTypeDictionary.getByName("Leaf explosion"); // MODDING expose this
		this.deconstructParticleEffect = particleEffectTypeDictionary.getByName("Dust cloud above"); // MODDING expose this
		this.gameInteractionStateContainer = gameInteractionStateContainer;

		messageDispatcher.addListener(this, MessageType.DESIGNATION_APPLIED);
		messageDispatcher.addListener(this, MessageType.REMOVE_DESIGNATION);
		messageDispatcher.addListener(this, MessageType.JOB_COMPLETED);
		messageDispatcher.addListener(this, MessageType.JOB_CREATED);
		messageDispatcher.addListener(this, MessageType.JOB_ASSIGNMENT_ACCEPTED);
		messageDispatcher.addListener(this, MessageType.JOB_ASSIGNMENT_CANCELLED);
		messageDispatcher.addListener(this, MessageType.REQUEST_PLANT_REMOVAL);
		messageDispatcher.addListener(this, MessageType.REQUEST_BRIDGE_REMOVAL);
		messageDispatcher.addListener(this, MessageType.REMOVE_HAULING_JOBS_TO_POSITION);
		messageDispatcher.addListener(this, MessageType.JOB_STATE_CHANGE);
	}

	@Override
	public boolean handleMessage(Telegram msg) {
		switch (msg.message) {
			case MessageType.DESIGNATION_APPLIED: {
				return handle((ApplyDesignationMessage) msg.extraInfo);
			}
			case MessageType.REMOVE_DESIGNATION: {
				return handle((RemoveDesignationMessage) msg.extraInfo);
			}
			case MessageType.JOB_COMPLETED: {
				return handleJobCompleted((JobCompletedMessage)msg.extraInfo);
			}
			case MessageType.JOB_ASSIGNMENT_ACCEPTED: {
				Job acceptedJob = (Job) msg.extraInfo;
				jobStore.switchState(acceptedJob, JobState.ASSIGNED);
				return true;
			}
			case MessageType.JOB_CREATED: {
				Job newJob = (Job) msg.extraInfo;
				if (newJob == null) {
					Logger.error("newJob received by " + this.getClass().getSimpleName() + " was null, investigate");
				} else {
					jobStore.add(newJob);
				}
				return true;
			}
			case MessageType.JOB_ASSIGNMENT_CANCELLED: {
				Job cancelledJob = (Job) msg.extraInfo;
				cancelledJob.setAssignedToEntityId(null);
				// Job may have already been removed e.g. hauling job completed on pickup then cancelled later
				if (jobStore.getAllJobs().containsKey(cancelledJob.getJobId())) {
					// FIXME Might need to remove more than just hauling and transfer liquid jobs
					if (cancelledJob.getType().isRemoveJobWhenAssignmentCancelled()) {
						jobStore.remove(cancelledJob);
					} else {
						jobStore.switchState(cancelledJob, JobState.POTENTIALLY_ACCESSIBLE);
					}
				}
				return true;
			}
			case MessageType.JOB_STATE_CHANGE: {
				return handle((JobStateMessage)msg.extraInfo);
			}
			case MessageType.REQUEST_PLANT_REMOVAL: {
				return handle((RequestPlantRemovalMessage)msg.extraInfo);
			}
			case MessageType.REQUEST_BRIDGE_REMOVAL: {
				Bridge bridgeToRemove = (Bridge) msg.extraInfo;
				if (bridgeToRemove.getDeconstructionJob() == null) {
					MapTile bridgeTile = pickLandTile(bridgeToRemove);
					if (bridgeTile != null) {
						Job deconstructionJob = jobFactory.deconstructionJob(bridgeTile);
						if (deconstructionJob != null) {
							bridgeToRemove.setDeconstructionJob(deconstructionJob);
							messageDispatcher.dispatchMessage(MessageType.JOB_CREATED, deconstructionJob);

							// apply deconstruction designation to all tiles
							for (Map.Entry<GridPoint2, BridgeTile> entry : bridgeToRemove.entrySet()) {
								MapTile tile = gameContext.getAreaMap().getTile(entry.getKey());
								if (tile.getDesignation() == null) {
									tile.setDesignation(GameInteractionMode.DECONSTRUCT.getDesignationToApply());
								}
							}

						}
					} else {
						Logger.error("Could not pick tile to deconstruct bridge from");
					}
				}
				return true;
			}
			case MessageType.REMOVE_HAULING_JOBS_TO_POSITION: {
				GridPoint2 location = (GridPoint2) msg.extraInfo;
				for (JobState jobState : JobState.values()) {
					for (Job job : jobStore.getCollectionByState(jobState).getAll()) {
						if (job != null && job.getType().equals(haulingJobType) && job.getHaulingAllocation() != null &&
								job.getHaulingAllocation().getTargetPosition() != null && job.getHaulingAllocation().getTargetPosition().equals(location)) {
							messageDispatcher.dispatchMessage(MessageType.JOB_REMOVED, job);
						}
					}
				}
				return true;
			}
			default:
				throw new IllegalArgumentException("Unexpected message type " + msg.message + " received by " + this.toString() + ", " + msg.toString());
		}
	}

	private boolean handleJobCompleted(JobCompletedMessage jobCompletedMessage) {
		Job completedJob = jobCompletedMessage.getJob();
		float skillLevelOfCompletion = 0f;
		if (jobCompletedMessage.getCompletedBy() != null) {
			skillLevelOfCompletion = jobCompletedMessage.getCompletedBy().getSkillLevel(completedJob.getRequiredProfession());
		}


		// Make neighbouring jobs of same type assignable // Should this apply to all job types? Probably?
		if (completedJob.getType().isAccessedFromAdjacentTile()) {
			TileNeighbours orthogonalNeighbours = gameContext.getAreaMap().getOrthogonalNeighbours(completedJob.getJobLocation().x, completedJob.getJobLocation().y);
			for (MapTile neighbourTile : orthogonalNeighbours.values()) {
				for (Job neighbourJob : jobStore.getJobsAtLocation(neighbourTile.getTilePosition())) {
					if (neighbourJob.getType().equals(completedJob.getType()) && neighbourJob.getJobState().equals(JobState.INACCESSIBLE)) {
						jobStore.switchState(neighbourJob, JobState.ASSIGNABLE);
					}
				}
			}
		}

		// Note that the designation and job are being removed before handling it, so removing walls does not try to remove the designation first
		MapTile tile = gameContext.getAreaMap().getTile(completedJob.getJobLocation());
		if (tile != null && tile.getDesignation() != null) {
			tile.setDesignation(null);
		}
		jobStore.remove(completedJob);

		if (completedJob.getType().getOnCompletionSoundAsset() != null) {
			messageDispatcher.dispatchMessage(MessageType.REQUEST_SOUND, new RequestSoundMessage(completedJob.getType().getOnCompletionSoundAsset(), jobCompletedMessage.getCompletedByEntity().getId(), jobCompletedMessage.getCompletedByEntity().getLocationComponent().getWorldOrParentPosition()));
		}

		// TODO Could the below be scripted rather than hard-coded?
		switch (completedJob.getType().getName()) {
			case "MINING": {

				MapTile targetTile = gameContext.getAreaMap().getTile(completedJob.getJobLocation());
				if (targetTile != null && targetTile.hasWall()) {
					Wall wall = targetTile.getWall();

					if (wall.hasOre()) {
						GameMaterial oreMaterial = wall.getOreMaterial();
						if (gameContext.getRandom().nextFloat() < skillLevelOfCompletion + 0.1f) {
							entityStore.createResourceItem(oreMaterial, completedJob.getJobLocation(), 1, wall.getMaterial());
						}
					} else {
						if (gameContext.getRandom().nextFloat() < skillLevelOfCompletion) {
							entityStore.createResourceItem(wall.getMaterial(), completedJob.getJobLocation(), 1);
						}
					}

					messageDispatcher.dispatchMessage(MessageType.REMOVE_WALL, completedJob.getJobLocation());

				}
				break;
			}
			case "LOGGING": {

				MapTile targetTile = gameContext.getAreaMap().getTile(completedJob.getJobLocation());
				Entity targetTree = null;
				if (targetTile != null && targetTile.hasTree()) {
					for (Entity entity : targetTile.getEntities()) {
						if (entity.getType().equals(EntityType.PLANT)) {
							targetTree = entity;
							break;
						}
					}
				}

				if (targetTree != null) {
					Vector2 worldPositionOfChoppingEntity = jobCompletedMessage.getCompletedByEntity().getLocationComponent().getWorldPosition();

					boolean fallToWest = true;
					if (worldPositionOfChoppingEntity.x < targetTree.getLocationComponent().getWorldPosition().x) {
						fallToWest = false;
					}

					FallingTreeBehaviour fallingTreeBehaviour = new FallingTreeBehaviour(fallToWest);
					entityStore.changeBehaviour(targetTree, fallingTreeBehaviour, messageDispatcher);
				}
				break;
			}
			case "TILLING": {
				messageDispatcher.dispatchMessage(MessageType.CHANGE_FLOOR,
						new ChangeFloorMessage(completedJob.getJobLocation(), completedJob.getReplacementFloorType(), completedJob.getReplacementFloorMaterial()));
				break;
			}
			case "PLANTING": {
				ItemType seedItemType = completedJob.getRequiredItemType();
				GameMaterial seedMaterial = completedJob.getRequiredItemMaterial();

				Entity completedByEntity = jobCompletedMessage.getCompletedByEntity();
				if (completedByEntity == null) {
					Logger.error("Entity that completed job is null");
				} else {
					InventoryComponent assignedEntityInventory = completedByEntity.getComponent(InventoryComponent.class);
					if (assignedEntityInventory != null) {
						InventoryComponent.InventoryEntry inventoryItem = assignedEntityInventory.findByItemTypeAndMaterial(seedItemType, seedMaterial, gameContext.getGameClock());
						if (inventoryItem == null || !inventoryItem.entity.getType().equals(ITEM)) {
							Logger.error("Could not find relevant inventory item");
						} else {
							ItemEntityAttributes attributes = (ItemEntityAttributes) inventoryItem.entity.getPhysicalEntityComponent().getAttributes();
							attributes.setQuantity(attributes.getQuantity() - 1); // FIXME handle planting quantities other than 1?
							if (attributes.getQuantity() <= 0) {
								messageDispatcher.dispatchMessage(MessageType.DESTROY_ENTITY, new EntityMessage(inventoryItem.entity.getId()));
							}
						}
					}
				}

				PlantSpecies plantSpecies = plantSpeciesDictionary.getBySeedMaterial(seedMaterial);
				if (plantSpecies == null) {
					Logger.error("Could not find plant species to grow from seed material: " + seedMaterial.getMaterialName());
				} else {
					PlantEntityAttributes attributes = plantEntityAttributesFactory.createBySpecies(plantSpecies, gameContext.getRandom());
					attributes.setGrowthStageProgress(0f);
					Entity plant = plantEntityFactory.create(attributes, completedJob.getJobLocation(), gameContext);
					// Fix to centre of tile
					plant.getLocationComponent().getWorldPosition().set(completedJob.getJobLocation().x + 0.5f, completedJob.getJobLocation().y + 0.5f);

					messageDispatcher.dispatchMessage(MessageType.ENTITY_CREATED, plant);
				}
				break;
			}
			case "REMOVE_PESTS_FROM_CROP": {
				Entity targetEntity = entityStore.getById(completedJob.getTargetId());
				if (targetEntity != null && targetEntity.getType().equals(PLANT)) {
					PlantEntityAttributes attributes = (PlantEntityAttributes) targetEntity.getPhysicalEntityComponent().getAttributes();
					attributes.clearAfflitctedByPests();
				}
				break;
			}
			case "HARVESTING": {

				Entity completedByEntity = jobCompletedMessage.getCompletedByEntity();
				if (completedByEntity != null && completedByEntity.getBehaviourComponent() instanceof SettlerBehaviour) {
					SettlerBehaviour settlerBehaviour = (SettlerBehaviour) completedByEntity.getBehaviourComponent();
					Entity targetEntity;
					if (completedJob.getTargetId() != null) {
						targetEntity = entityStore.getById(completedJob.getTargetId());
					} else {
						targetEntity = gameContext.getAreaMap().getTile(completedJob.getJobLocation()).getPlant();
					}
					if (targetEntity != null && targetEntity.getType().equals(PLANT)) {
						PlantEntityAttributes attributes = (PlantEntityAttributes) targetEntity.getPhysicalEntityComponent().getAttributes();

						PlantSpeciesGrowthStage currentGrowthStage = attributes.getSpecies().getGrowthStages().get(attributes.getGrowthStageCursor());
						if (currentGrowthStage.getHarvestType() != null) {
							for (PlantSpeciesItem harvestedItem : currentGrowthStage.getHarvestedItems()) {
								harvest(harvestedItem, targetEntity, completedByEntity, settlerBehaviour);
							}

							if (currentGrowthStage.getHarvestSwitchesToGrowthStage() == null) {
								messageDispatcher.dispatchMessage(MessageType.PARTICLE_REQUEST, new ParticleRequestMessage(leafExplosionParticleEffectType,
										Optional.empty(), Optional.of(new JobTarget(targetEntity)), (p) -> {}));
								messageDispatcher.dispatchMessage(MessageType.DESTROY_ENTITY, new EntityMessage(targetEntity.getId()));
							} else {
								attributes.setGrowthStageCursor(currentGrowthStage.getHarvestSwitchesToGrowthStage());
								attributes.setGrowthStageProgress(0f);
								messageDispatcher.dispatchMessage(MessageType.ENTITY_ASSET_UPDATE_REQUIRED, targetEntity);
							}
						} else {
							Logger.error("Attempting to harvest a plant without a current growth stage harvest type (might have taken too long to get to the job?)");
						}
					} else if (targetEntity != null && targetEntity.getType().equals(FURNITURE)) {
						HarvestableEntityComponent harvestableEntityComponent = targetEntity.getComponent(HarvestableEntityComponent.class);
						if (harvestableEntityComponent != null) {
							for (PlantSpeciesItem harvestedItem : harvestableEntityComponent.getAll()) {
								harvest(harvestedItem, targetEntity, completedByEntity, settlerBehaviour);
							}
							harvestableEntityComponent.clear();
							DecorationInventoryComponent decorationInventoryComponent = targetEntity.getComponent(DecorationInventoryComponent.class);
							if (decorationInventoryComponent != null) {
								decorationInventoryComponent.clear();
							}
						} else {
							Logger.error("Harvesting from entity without " + HarvestableEntityComponent.class.getSimpleName());
						}
					} else {
						Logger.error("Target of " + completedJob.getType().getName() + " job is not valid");
					}
				} else {
					Logger.error("Entity that completed job is null or not a settler");
				}
				break;
			}
			case "MOVE_LIQUID_IN_ITEM":
			case "TRANSFER_LIQUID":
			{
				Entity completedByEntity = jobCompletedMessage.getCompletedByEntity();
				HaulingComponent haulingComponent = completedByEntity.getComponent(HaulingComponent.class);
				if (haulingComponent != null && haulingComponent.getHauledEntity() != null) {
					Entity hauledItem = haulingComponent.getHauledEntity();
					LiquidContainerComponent sourceLiquidContainer = hauledItem.getComponent(LiquidContainerComponent.class);
					if (sourceLiquidContainer != null) {
						Entity targetEntity = entityStore.getById(completedJob.getTargetId());
						if (targetEntity != null) {
							LiquidContainerComponent targetLiquidContainer = targetEntity.getComponent(LiquidContainerComponent.class);
							if (targetLiquidContainer != null) {
								if (targetLiquidContainer.getTargetLiquidMaterial().equals(sourceLiquidContainer.getTargetLiquidMaterial())) {
									float availableSpace = targetLiquidContainer.getMaxLiquidCapacity() - targetLiquidContainer.getLiquidQuantity();
									float quantityToTransfer = Math.min(availableSpace, sourceLiquidContainer.getLiquidQuantity());

									sourceLiquidContainer.setLiquidQuantity(sourceLiquidContainer.getLiquidQuantity() - quantityToTransfer);
									targetLiquidContainer.setLiquidQuantity(targetLiquidContainer.getLiquidQuantity() + quantityToTransfer);
									messageDispatcher.dispatchMessage(MessageType.LIQUID_SPLASH, new LiquidSplashMessage(targetEntity, targetLiquidContainer.getTargetLiquidMaterial()));

									if (targetEntity.getBehaviourComponent() instanceof CraftingStationBehaviour) {
										CraftingStationBehaviour craftingStationBehaviour = (CraftingStationBehaviour) targetEntity.getBehaviourComponent();
										craftingStationBehaviour.liquidAdded(quantityToTransfer, gameContext.getAreaMap());
									}

								} else {
									Logger.error("Attempting to combine different liquid materials");
								}
							} else {
								Logger.error("Could not find target liquid container when completing " + completedJob.getType().getName());
							}
						} else {
							Logger.error("Could not find target entity when completing " + completedJob.getType().getName());
						}
					} else {
						Logger.error("Source liquid container not found on hauled item");
					}
				} else {
					Logger.error("Could not find hauled item when completing " + completedJob.getType().getName() + " job");
				}

				break;
			}
			case "COOKING":  {
				Entity targetFurnitureEntity = entityStore.getById(completedJob.getTargetId());
				if (targetFurnitureEntity != null) {
					CookingRecipe recipe = completedJob.getCookingRecipe();
					GameMaterial outputMaterial = null;
					InventoryComponent inventoryComponent = targetFurnitureEntity.getOrCreateComponent(InventoryComponent.class);

					switch (recipe.getOutputProcess()) {
						case COMBINE_ITEM_MATERIALS: {
							List<GameMaterial> inputMaterials = new ArrayList<>();
							for (InventoryComponent.InventoryEntry entry : inventoryComponent.getInventoryEntries()) {
								if (entry.entity.getType().equals(ITEM)) {
									ItemEntityAttributes attributes = (ItemEntityAttributes) entry.entity.getPhysicalEntityComponent().getAttributes();
									inputMaterials.add(attributes.getMaterial(attributes.getItemType().getPrimaryMaterialType()));
								}
							}

							outputMaterial = dynamicMaterialFactory.generate(inputMaterials, recipe.getOutputMaterialType(), false, true,
									recipe.getOutputDescriptionI18nKey());

							break;
						}
						case PICK_MOST_COMMON_ITEM_MATERIAL: {
							int mostCommonCounter = 0;
							for (InventoryComponent.InventoryEntry entry : inventoryComponent.getInventoryEntries()) {
								if (entry.entity.getType().equals(ITEM)) {
									ItemEntityAttributes attributes = (ItemEntityAttributes) entry.entity.getPhysicalEntityComponent().getAttributes();
									if (attributes.getQuantity() > mostCommonCounter) {
										outputMaterial = attributes.getMaterial(attributes.getItemType().getPrimaryMaterialType());
										mostCommonCounter = attributes.getQuantity();
									}
								}
							}
							break;
						}
						case SPECIFIED_MATERIAL: {
							outputMaterial = recipe.getOutputMaterial();
							break;
						}
						default:
							Logger.error("Not yet implemented, completion of " + completedJob + " with cooking out process " + recipe.getOutputProcess());
					}

					switch (recipe.getOutputMaterialType()) {
						case LIQUID: {
							LiquidContainerComponent liquidContainerComponent = targetFurnitureEntity.getComponent(LiquidContainerComponent.class);
							if (liquidContainerComponent != null) {
								liquidContainerComponent.setTargetLiquidMaterial(outputMaterial);
								liquidContainerComponent.setLiquidQuantity(recipe.getOutputQuantity());

								inventoryComponent.destroyAllEntities(messageDispatcher);
							} else {
								Logger.error("Could not find " + LiquidContainerComponent.class.getSimpleName() + " when completing " + completedJob);
							}
							break;
						}
						case FOODSTUFF: {
							inventoryComponent.destroyAllEntities(messageDispatcher);

							ItemEntityAttributes attributes = new ItemEntityAttributes(gameContext.getRandom().nextLong());
							attributes.setItemType(recipe.getOutputItemType());
							attributes.setQuantity(recipe.getOutputQuantity());
							attributes.setMaterial(outputMaterial);
							Entity outputItem = itemEntityFactory.create(attributes, null, true, gameContext);

							inventoryComponent.add(outputItem, targetFurnitureEntity, messageDispatcher, gameContext.getGameClock());

							// Unallocate to allow hauling away
							outputItem.getOrCreateComponent(ItemAllocationComponent.class).cancelAll(HELD_IN_INVENTORY);
							// KitchenManager will organise movement of food to serving tables

							break;
						}
						default:
							Logger.error("Not yet implemented, recipe output material type " + recipe.getOutputMaterialType());
					}


					messageDispatcher.dispatchMessage(MessageType.COOKING_COMPLETE, new CookingCompleteMessage(targetFurnitureEntity, completedJob.getCookingRecipe()));
				} else {
					Logger.error("Can not find furniture entity for " + completedJob);
				}
				break;
			}
			case "CLEAR_GROUND":

				MapTile targetTile = gameContext.getAreaMap().getTile(completedJob.getJobLocation());
				Entity targetPlant = null;
				if (targetTile != null && targetTile.hasPlant()) {
					for (Entity entity : targetTile.getEntities()) {
						if (entity.getType().equals(EntityType.PLANT)) {
							targetPlant = entity;
							break;
						}
					}
				}

				if (targetPlant != null) {
					messageDispatcher.dispatchMessage(MessageType.PARTICLE_REQUEST, new ParticleRequestMessage(leafExplosionParticleEffectType,
							Optional.empty(), Optional.of(new JobTarget(targetPlant)), (p) -> {}));
					messageDispatcher.dispatchMessage(MessageType.DESTROY_ENTITY, new EntityMessage(targetPlant.getId()));
				}
				break;
			case "DIGGING":
			case "CONSTRUCT_STONE_FURNITURE":
			case "CONSTRUCT_WOODEN_FURNITURE":
			case "CONSTRUCT":
				targetTile = gameContext.getAreaMap().getTile(completedJob.getJobLocation());
				Construction construction = targetTile.getConstruction();
				messageDispatcher.dispatchMessage(MessageType.CONSTRUCTION_COMPLETED, construction);
				break;
			case "SHOVELLING":
			case "PRODUCE_ITEM":
			case "PRODUCE_LIQUID":
			case "CRAFT_ITEM":
			case "FORGE_ITEM":
				// Hoping targetEntity is a furniture such as crafting station
				notifyTargetEntityJobCompleted(completedJob);
				break;
			case "DECONSTRUCT":
				targetTile = gameContext.getAreaMap().getTile(completedJob.getJobLocation());
				// Might be wall, doorway or furniture entity
				if (targetTile.hasWall()) {
					Wall wall = targetTile.getWall(); // Need to grab wall before it is destroyed
					messageDispatcher.dispatchMessage(MessageType.REMOVE_WALL, completedJob.getJobLocation());
					if (wall.getWallType().isConstructed()) {
						ItemEntityAttributes itemAttributes = itemEntityAttributesFactory.resourceFromWall(wall);
						if (itemAttributes != null) {
							itemEntityFactory.create(itemAttributes, targetTile.getTilePosition(), true, gameContext);
						}
					}
				} else if (targetTile.hasDoorway()) {
					Doorway doorway = targetTile.getDoorway();
					messageDispatcher.dispatchMessage(MessageType.DECONSTRUCT_DOOR, doorway);
				} else if (targetTile.getFloor().hasBridge()) {
					Bridge bridge = targetTile.getFloor().getBridge();
					messageDispatcher.dispatchMessage(MessageType.DECONSTRUCT_BRIDGE, bridge);
				} else {
					// Assuming we're deconstructing a furniture entity in the target tile
					Entity targetEntity = null;
					for (Entity entity : targetTile.getEntities()) {
						if (entity.getType().equals(EntityType.FURNITURE)) {
							targetEntity = entity;
							break;
						}
					}

					if (targetEntity == null) {
						Logger.error("Could not find furniture entity to deconstruct in " + targetTile);
					} else {
						deconstructFurniture(targetEntity, targetTile, messageDispatcher, gameContext, itemTypeDictionary, itemEntityAttributesFactory, itemEntityFactory, deconstructParticleEffect);
					}

				}
				break;
			case "MUSHROOM_INNOCULATION": {
				Entity targetEntity = entityStore.getById(completedJob.getTargetId());
				if (targetEntity != null && targetEntity.getType().equals(FURNITURE)) {
					BehaviourComponent behaviourComponent = targetEntity.getBehaviourComponent();
					if (behaviourComponent instanceof InnoculationLogBehaviour) {
						InnoculationLogBehaviour innoculationLogBehaviour = (InnoculationLogBehaviour) behaviourComponent;

						InventoryComponent inventoryComponent = targetEntity.getComponent(InventoryComponent.class);
						Entity relatedInventoryItem = null;
						for (InventoryComponent.InventoryEntry inventoryEntry : inventoryComponent.getInventoryEntries()) {
							ItemEntityAttributes itemEntityAttributes = (ItemEntityAttributes) inventoryEntry.entity.getPhysicalEntityComponent().getAttributes();
							if (itemEntityAttributes.getItemType().equals(innoculationLogBehaviour.getRelatedItemType())) {
								relatedInventoryItem = inventoryEntry.entity;

								GameMaterial spawnMaterial = itemEntityAttributes.getMaterial(GameMaterialType.SEED);
								((FurnitureEntityAttributes)targetEntity.getPhysicalEntityComponent().getAttributes()).setMaterial(spawnMaterial);
								innoculationLogBehaviour.setState(INNOCULATING);
								break;
							}
						}

						if (relatedInventoryItem != null) {
							messageDispatcher.dispatchMessage(MessageType.DESTROY_ENTITY, new EntityMessage(relatedInventoryItem.getId()));
						} else {
							Logger.error("Could not find correct item in " + InnoculationLogBehaviour.class + " inventory");
						}
					} else {
						Logger.error("Target of " + completedJob.getType().getName() + " does not have " + InnoculationLogBehaviour.class.getSimpleName());
					}
				} else {
					Logger.error("Could not find furniture entity to innoculate ");
				}

				break;
			}
			case "FILL_GRAVE": {
				Entity targetEntity = entityStore.getById(completedJob.getTargetId());
				if (targetEntity != null && targetEntity.getType().equals(FURNITURE)) {
					DeceasedContainerTag deceasedContainerTag = targetEntity.getTag(DeceasedContainerTag.class);
					if (!deceasedContainerTag.getArgs().isEmpty()) {
						FurnitureType transformationType = furnitureTypeDictionary.getByName(deceasedContainerTag.getArgs().get(0));
						if (transformationType != null) {
							messageDispatcher.dispatchMessage(MessageType.TRANSFORM_FURNITURE_TYPE,
									new TransformFurnitureMessage(targetEntity, transformationType));
						} else {
							Logger.error("Could not find furniture type to transform to " + deceasedContainerTag.getArgs().get(0) + " for " + deceasedContainerTag.getClass().getSimpleName());
						}
					} else {
						Logger.error("Filling grave that does not have a furniture type to transform to");
					}
				} else {
					Logger.error("Could not find furniture entity for " + completedJob.getType().getName() + " job completion");
				}
				break;
			}
			case "HAULING":
			case "COLLECT_ITEM":
			case "REMOVE_LIQUID":
				// Nothing special to be done
				break;
			case "DUMP_LIQUID_FROM_CONTAINER": {
				Entity targetEntity = entityStore.getById(completedJob.getHaulingAllocation().getHauledEntityId());
				if (targetEntity != null && targetEntity.getType().equals(ITEM)) {

					LiquidContainerComponent liquidContainerComponent = targetEntity.getComponent(LiquidContainerComponent.class);
					if (liquidContainerComponent != null) {
						messageDispatcher.dispatchMessage(MessageType.LIQUID_SPLASH, new LiquidSplashMessage(jobCompletedMessage.getCompletedByEntity(),
								liquidContainerComponent.getTargetLiquidMaterial()));

						liquidContainerComponent.setLiquidQuantity(0);
						liquidContainerComponent.setTargetLiquidMaterial(null);
					}
				} else {
					Logger.error("Could not find item entity for " + completedJob.getType().getName() + " job completion");
				}
				break;
			}
			case "CONSTRUCT_FLOORING": {
				Entity completedByEntity = jobCompletedMessage.getCompletedByEntity();
				EquippedItemComponent equippedItemComponent = completedByEntity.getOrCreateComponent(EquippedItemComponent.class);
				if (equippedItemComponent != null) {
					Entity equippedItem = equippedItemComponent.clearEquippedItem();
					if (equippedItem != null && equippedItem.getType().equals(ITEM)) {
						ItemEntityAttributes attributes = (ItemEntityAttributes) equippedItem.getPhysicalEntityComponent().getAttributes();
						GameMaterial material = attributes.getPrimaryMaterial();
						attributes.setQuantity(attributes.getQuantity() - 1);
						if (attributes.getQuantity() == 0) {
							messageDispatcher.dispatchMessage(MessageType.DESTROY_ENTITY, new EntityMessage(equippedItem.getId()));
						} else {
							// put back as equipped for AI to clear
							equippedItemComponent.setEquippedItem(equippedItem, completedByEntity, messageDispatcher);
						}

						messageDispatcher.dispatchMessage(MessageType.FLOORING_CONSTRUCTED, new FloorConstructionMessage(
								jobCompletedMessage.getJob().getJobLocation(), attributes.getItemType(), material
						));

					}
				}
				break;
			}
			case "CONSTRUCT_ROOFING": {
				Entity completedByEntity = jobCompletedMessage.getCompletedByEntity();
				EquippedItemComponent equippedItemComponent = completedByEntity.getOrCreateComponent(EquippedItemComponent.class);
				if (equippedItemComponent != null) {
					Entity equippedItem = equippedItemComponent.clearEquippedItem();
					if (equippedItem != null && equippedItem.getType().equals(ITEM)) {
						ItemEntityAttributes attributes = (ItemEntityAttributes) equippedItem.getPhysicalEntityComponent().getAttributes();
						GameMaterial material = attributes.getPrimaryMaterial();
						attributes.setQuantity(attributes.getQuantity() - 1);
						if (attributes.getQuantity() == 0) {
							messageDispatcher.dispatchMessage(MessageType.DESTROY_ENTITY, new EntityMessage(equippedItem.getId()));
						} else {
							// put back as equipped for AI to clear
							equippedItemComponent.setEquippedItem(equippedItem, completedByEntity, messageDispatcher);
						}

						messageDispatcher.dispatchMessage(MessageType.ROOF_CONSTRUCTED, new RoofConstructionMessage(
							jobCompletedMessage.getJob().getJobLocation(), material
						));

					}
				}
				break;
			}
			case "DECONSTRUCT_ROOFING": {
				messageDispatcher.dispatchMessage(MessageType.ROOF_DECONSTRUCTED, new RoofConstructionMessage(
						jobCompletedMessage.getJob().getJobLocation(), NULL_MATERIAL
				));
				break;
			}
			default: {
				Logger.error("Not yet implemented job completion: " + completedJob.getType());
			}
		}

		return true;
	}

	private void notifyTargetEntityJobCompleted(Job completedJob) {
		Entity craftingCompleteTargetEntity = entityStore.getById(completedJob.getTargetId());
		if (craftingCompleteTargetEntity != null && craftingCompleteTargetEntity.getBehaviourComponent() instanceof OnJobCompletion) {
			((OnJobCompletion)craftingCompleteTargetEntity.getBehaviourComponent()).jobCompleted(gameContext);
		}
	}

	private boolean handle(RequestPlantRemovalMessage message) {
		// Only apply if no job exists
		if (jobStore.getJobsAtLocation(message.getTileLocation()).isEmpty()) {

			PlantEntityAttributes attributes = (PlantEntityAttributes) message.getPlantEntityToRemove().getPhysicalEntityComponent().getAttributes();
			if (message.getPlantEntityToRemove().getBehaviourComponent() instanceof FallingTreeBehaviour) {
				// Tree is already falling over
				return true;
			}

			MapTile targetTile = gameContext.getAreaMap().getTile(message.getTileLocation());

			JobType removalJobType = attributes.getSpecies().getPlantType().getRemovalJobType();
			if (targetTile.getDesignation() == null) {
				// show this as a designation to help player understanding
				TileDesignation designationToApply = getMatchingTileDesignation(removalJobType);
				if (designationToApply != null) {
					targetTile.setDesignation(designationToApply);
				}
			}

			Job removalJob = new Job(removalJobType);
			removalJob.setTargetId(message.getPlantEntityToRemove().getId());
			removalJob.setJobPriority(message.jobPriority);
			removalJob.setJobLocation(message.getTileLocation());
			jobStore.add(removalJob);
			if (message.callback != null) {
				message.callback.jobCreated(removalJob);
			}
		}

		return true;
	}

	private void harvest(PlantSpeciesItem harvestedItem, Entity targetEntity, Entity completedByEntity, SettlerBehaviour settlerBehaviour) {
		InventoryComponent inventoryComponent = completedByEntity.getComponent(InventoryComponent.class);
		if (harvestedItem.getItemType() == null || harvestedItem.getMaterial() == null) {
			Logger.error("Attempting to harvest unrecognised item");
			return;
		}

		Entity createdItem = itemEntityFactory.createByItemType(harvestedItem.getItemType(), gameContext, true);
		ItemEntityAttributes createdAttributes = (ItemEntityAttributes) createdItem.getPhysicalEntityComponent().getAttributes();
		GameMaterial oldPrimaryMaterial = createdAttributes.getPrimaryMaterial();
		createdAttributes.setQuantity(harvestedItem.getQuantity());
		createdAttributes.setMaterial(harvestedItem.getMaterial());
		if (!oldPrimaryMaterial.equals(createdAttributes.getPrimaryMaterial())) {
			messageDispatcher.dispatchMessage(MessageType.ITEM_PRIMARY_MATERIAL_CHANGED, new ItemPrimaryMaterialChangedMessage(createdItem, oldPrimaryMaterial));
		}

		boolean needsHaulingNow = false;
		InventoryComponent.InventoryEntry inventoryEntry;
		inventoryEntry = inventoryComponent.add(createdItem, completedByEntity, messageDispatcher, gameContext.getGameClock());

		int quantityInInventory = ((ItemEntityAttributes) inventoryEntry.entity.getPhysicalEntityComponent().getAttributes()).getQuantity();
		if (quantityInInventory >= createdAttributes.getItemType().getMaxStackSize() - harvestedItem.getQuantity()) {
			needsHaulingNow = true;
		}

		if (needsHaulingNow) {
			if (harvestedItem.getItemType().isEquippedWhileWorkingOnJob()) {
				// Switch to hauling
				inventoryComponent.remove(inventoryEntry.entity.getId());
				HaulingComponent haulingComponent = completedByEntity.getOrCreateComponent(HaulingComponent.class);
				haulingComponent.setHauledEntity(inventoryEntry.entity, messageDispatcher, completedByEntity);
			} else {
				// Remove all other goals and set this inventory item to expired so it is immediately placed
				settlerBehaviour.getGoalQueue().clear();
				inventoryEntry.setLastUpdateGameTime(0 - harvestedItem.getItemType().getHoursInInventoryUntilUnused());
			}
		}
	}

	public static void deconstructFurniture(Entity targetEntity, MapTile targetTile, MessageDispatcher messageDispatcher,
											GameContext gameContext, ItemTypeDictionary itemTypeDictionary,
											ItemEntityAttributesFactory itemEntityAttributesFactory, ItemEntityFactory itemEntityFactory, ParticleEffectType deconstructParticleEffect) {
		// Extra check to see deconstruction is allowed
		ConstructedEntityComponent constructedEntityComponent = targetEntity.getComponent(ConstructedEntityComponent.class);
		if (constructedEntityComponent != null && !constructedEntityComponent.canBeDeconstructed()) {
			return;
		}

		SupportsRoofTag supportsRoofTag = targetEntity.getTag(SupportsRoofTag.class);

		List<ItemEntityAttributes> itemAttributeList = itemEntityAttributesFactory.resourcesFromFurniture(targetEntity);
		ReplacementDeconstructionResourcesTag replacementDeconstructionResourcesTag = targetEntity.getTag(ReplacementDeconstructionResourcesTag.class);
		if (replacementDeconstructionResourcesTag != null) {
			List<ItemType> replacementItems = new ArrayList<>();
			for (String arg : replacementDeconstructionResourcesTag.getArgs()) {
				ItemType itemType = itemTypeDictionary.getByName(arg);
				if (itemType != null) {
					replacementItems.add(itemType);
				} else {
					Logger.error("Could not find itemType with name " + arg + " from " + ReplacementDeconstructionResourcesTag.class.getSimpleName());
				}
			}
			itemAttributeList = itemEntityAttributesFactory.resourcesFromFurniture(targetEntity, replacementItems);
		}

		List<GridPoint2> targetPositions = new ArrayList<>();
		targetPositions.add(targetTile.getTilePosition());
		FurnitureEntityAttributes furnitureEntityAttributes = (FurnitureEntityAttributes) targetEntity.getPhysicalEntityComponent().getAttributes();
		for (GridPoint2 extraTileOffset : furnitureEntityAttributes.getCurrentLayout().getExtraTiles()) {
			targetPositions.add(targetTile.getTilePosition().cpy().add(extraTileOffset));
		}
		Collections.shuffle(targetPositions);

		for (GridPoint2 targetPosition : targetPositions) {
			gameContext.getAreaMap().getTile(targetPosition).setDesignation(null);

			messageDispatcher.dispatchMessage(MessageType.PARTICLE_REQUEST, new ParticleRequestMessage(deconstructParticleEffect,
					Optional.empty(), Optional.of(new JobTarget(targetTile)), (p) -> {}));
		}

		messageDispatcher.dispatchMessage(MessageType.DESTROY_ENTITY, new EntityMessage(targetEntity.getId()));
		for (ItemEntityAttributes itemAttributes : itemAttributeList) {
			GridPoint2 targetPosition = targetTile.getTilePosition();
			if (!targetPositions.isEmpty()) {
				targetPosition = targetPositions.remove(0);
			}
			itemEntityFactory.create(itemAttributes, targetPosition, true, gameContext);
		}

		// Check for collapse after removal
		if (supportsRoofTag != null) {
			messageDispatcher.dispatchMessage(MessageType.ROOF_SUPPORT_REMOVED, targetTile.getTilePosition());
		}
	}

	private boolean handle(ApplyDesignationMessage applyDesignationMessage) {
		JobType jobType = applyDesignationMessage.getDesignationToApply().getCreatesJobType();
		if (jobType != null) {
			Job newJob = null;
			if (jobType.equals(miningJobType)) {
				// Special case for mining a constructed wall
				MapTile targetTile = applyDesignationMessage.getTargetTile();
				Wall wall = targetTile.getWall();
				if (wall != null) {
					if (wall.getWallType().isConstructed()) {
						newJob = jobFactory.deconstructionJob(applyDesignationMessage.getTargetTile());
					}
				}
			}

			if (newJob == null) {
				// Not triggered special case
				newJob = new Job(jobType);
			}

			if (jobType.equals(constructFlooringJobType)) {
				FloorType floorTypeToPlace = gameInteractionStateContainer.getFloorTypeToPlace();
				MaterialSelectionMessage materialSelection = gameInteractionStateContainer.getFloorMaterialSelection();
				newJob.setRequiredItemType(floorTypeToPlace.getRequirements().get(floorTypeToPlace.getMaterialType()).get(0).getItemType());
				if (materialSelection.selectedMaterial != null && !materialSelection.selectedMaterial.equals(NULL_MATERIAL)) {
					newJob.setRequiredItemMaterial(materialSelection.selectedMaterial);
				}
				newJob.setRequiredProfession(floorTypeToPlace.getCraftingType().getProfessionRequired());
			}

			newJob.setJobLocation(applyDesignationMessage.getTargetTile().getTilePosition());
			newJob.setJobState(calculateNewJobState(jobType, applyDesignationMessage.getTargetTile()));

			jobStore.add(newJob);
		}

		switch (applyDesignationMessage.getInteractionMode()) {
			case REMOVE_CONSTRUCTIONS:
				if (applyDesignationMessage.getTargetTile().hasConstruction()) {
					messageDispatcher.dispatchMessage(MessageType.CANCEL_CONSTRUCTION, applyDesignationMessage.getTargetTile().getConstruction());
				}
				TileDesignation designation = applyDesignationMessage.getTargetTile().getDesignation();
				if (designation != null) {
					messageDispatcher.dispatchMessage(MessageType.REMOVE_DESIGNATION, new RemoveDesignationMessage(applyDesignationMessage.getTargetTile(), designation));
				}
				break;
			case DECONSTRUCT:
				// deconstruction also applies designation
				Optional<Entity> optionalFurniture = applyDesignationMessage.getTargetTile().getEntities().stream().filter(e -> e.getType().equals(FURNITURE)).findAny();

				if (optionalFurniture.isEmpty() && applyDesignationMessage.getTargetTile().hasDoorway()) {
					optionalFurniture = Optional.of(applyDesignationMessage.getTargetTile().getDoorway().getDoorEntity());
				}

				if (optionalFurniture.isPresent()) {
					ConstructedEntityComponent constructedEntityComponent = optionalFurniture.get().getComponent(ConstructedEntityComponent.class);
					if (constructedEntityComponent != null && !constructedEntityComponent.isBeingDeconstructed()) {
						messageDispatcher.dispatchMessage(MessageType.REQUEST_FURNITURE_REMOVAL, optionalFurniture.get());
					}
				}

				if (applyDesignationMessage.getTargetTile().hasWall() && applyDesignationMessage.getTargetTile().getWall().getWallType().isConstructed()) {
					Job deconstructionJob = jobFactory.deconstructionJob(applyDesignationMessage.getTargetTile());
					messageDispatcher.dispatchMessage(MessageType.JOB_CREATED, deconstructionJob);
				}

				if (applyDesignationMessage.getTargetTile().getFloor().hasBridge()) {
					messageDispatcher.dispatchMessage(MessageType.REQUEST_BRIDGE_REMOVAL, applyDesignationMessage.getTargetTile().getFloor().getBridge());
				}

				break;
		}

		return true;
	}

	private boolean handle(RemoveDesignationMessage removeDesignationMessage) {
		JobType jobType = removeDesignationMessage.getDesignationToRemove().getCreatesJobType();
		if (jobType != null) {
			List<Job> jobsAtLocation = jobStore.getJobsAtLocation(removeDesignationMessage.getTargetTile().getTilePosition());
			List<Job> jobsToRemove = new LinkedList<>();
			for (Job job : jobsAtLocation) {
				if (job.getType().equals(jobType)) {
					jobsToRemove.add(job);
				}
			}

			for (Job job : jobsToRemove) {
				if (job.getAssignedToEntityId() != null) {
					messageDispatcher.dispatchMessage(MessageType.JOB_REMOVED, job);
				}
				jobStore.remove(job);
			}
		}
		removeDesignationMessage.getTargetTile().setDesignation(null);
		return true;
	}

	private boolean handle(JobStateMessage jobStateChangeMessage) {
		jobStore.switchState(jobStateChangeMessage.job, jobStateChangeMessage.newState);
		return true;
	}

	private TileDesignation getMatchingTileDesignation(JobType jobType) {
		for (TileDesignation tileDesignation : tileDesignationDictionary.getAll()) {
			if (jobType.equals(tileDesignation.getCreatesJobType())) {
				return tileDesignation;
			}
		}
		return null;
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
	}

	@Override
	public void clearContextRelatedState() {

	}

	private JobState calculateNewJobState(JobType jobType, MapTile targetTile) {
		if (jobType.isAccessedFromAdjacentTile()) {
			boolean neighbourIsAccessible = false;

			TileNeighbours orthogonalNeighbours = gameContext.getAreaMap().getOrthogonalNeighbours(targetTile.getTileX(), targetTile.getTileY());
			for (MapTile neighbourTile : orthogonalNeighbours.values()) {
				if (neighbourTile.isNavigable()) {
					neighbourIsAccessible = true;
					break;
				}
			}

			if (neighbourIsAccessible) {
				return JobState.POTENTIALLY_ACCESSIBLE;
			} else {
				return JobState.INACCESSIBLE;
			}
		} else {
			if (targetTile.isNavigable()) {
				return JobState.POTENTIALLY_ACCESSIBLE;
			} else {
				return JobState.INACCESSIBLE;
			}
		}

	}

	private MapTile pickLandTile(Bridge bridge) {
		for (GridPoint2 location : bridge.getLocations()) {
			MapTile mapTile = gameContext.getAreaMap().getTile(location);
			if (mapTile != null && !mapTile.getFloor().isRiverTile()) {
				return mapTile;
			}
		}
		return null;
	}
}
