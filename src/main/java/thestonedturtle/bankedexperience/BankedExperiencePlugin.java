package thestonedturtle.bankedexperience;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import thestonedturtle.bankedexperience.data.Activity;
import thestonedturtle.bankedexperience.data.BankedItem;
import thestonedturtle.bankedexperience.data.ExperienceItem;

@Slf4j
@PluginDescriptor(
	name = "Banked Experience"
)
public class BankedExperiencePlugin extends Plugin
{
	private final static BufferedImage ICON = ImageUtil.getResourceStreamFromClass(BankedExperiencePlugin.class, "banked.png");

	public static final String CONFIG_GROUP = "bankedexperience";

	public static final String CONFIG_KEY = "maps";

	private String fileNameBankMap = "src/main/output/bank_map.json";

	private String fileNameSeedVaultMap = "src/main/output/seed_vault_map.json";

	private String fileNameAllItemsMap = "src/main/output/all_items_map.json";

	private String fileNameBankedItemMap = "src/main/output/banked_item_map.json";

	private String fileNameLinkedMap = "src/main/output/linked_map.json";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private BankedExperienceConfig config;

	@Provides
	BankedExperienceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankedExperienceConfig.class);
	}

	private NavigationButton navButton;

	private BankedCalculatorPanel panel;

	private boolean prepared = false;

	private int bankHash = -1;

	private int lastCheckTick = -1;

	@Override
	protected void startUp() throws Exception
	{
		panel = new BankedCalculatorPanel(this, client, config, skillIconManager, itemManager);
		navButton = NavigationButton.builder()
			.tooltip("Banked XP")
			.icon(ICON)
			.priority(6)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		if (!prepared)
		{
			clientThread.invoke(() ->
			{
				switch (client.getGameState())
				{
					case LOGIN_SCREEN:
					case LOGIN_SCREEN_AUTHENTICATOR:
					case LOGGING_IN:
					case LOADING:
					case LOGGED_IN:
					case CONNECTION_LOST:
					case HOPPING:
						ExperienceItem.prepareItemCompositions(itemManager);
						Activity.prepareItemCompositions(itemManager);
						prepared = true;
						return true;
					default:
						return false;
				}
			});
		}

		clientThread.invokeLater(() ->
		{
			loadUsingBufferedWriter(fileNameBankMap);
			loadUsingBufferedWriter(fileNameSeedVaultMap);
			loadUsingBufferedWriter(fileNameAllItemsMap);
			loadUsingBufferedWriter(fileNameBankedItemMap);
			loadUsingBufferedWriter(fileNameLinkedMap);
		});
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		saveItemBankedMultimap(panel.getLinkedMap(), fileNameLinkedMap);
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!event.getEventName().equals("setBankTitle") || client.getTickCount() == lastCheckTick)
		{
			return;
		}

		// Check if the contents have changed.
		final ItemContainer c = client.getItemContainer(InventoryID.BANK);
		if (c == null)
		{
			return;
		}

		final Item[] widgetItems = c.getItems();
		if (widgetItems == null || widgetItems.length == 0)
		{
			return;
		}

		final Map<Integer, Integer> m = new HashMap<>();
		for (Item widgetItem : widgetItems)
		{
			m.put(widgetItem.getId(), widgetItem.getQuantity());
		}

		final int curHash = m.hashCode();
		if (bankHash != curHash)
		{
			bankHash = curHash;
			SwingUtilities.invokeLater(() -> panel.setBankMap(m));
			updateBankedItemMap();
		}

		lastCheckTick = client.getTickCount();
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != WidgetID.SEED_VAULT_GROUP_ID)
		{
			return;
		}

		updateSeedVaultMap();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();

		if (containerId == InventoryID.BANK.getId())
		{
			updateBankMap();
		}
		else if (containerId == InventoryID.SEED_VAULT.getId())
		{
			updateSeedVaultMap();
		}
	}

	public void updateLinkedMap()
	{
		log.info("Linked Map: {}", panel.getLinkedMap());
		saveItemBankedMultimap(panel.getLinkedMap(), fileNameLinkedMap);
	}

	private void updateBankedItemMap()
	{
		Map<Integer, Integer> map = new HashMap<>();
		Map<ExperienceItem, BankedItem> bankedItemMap = new HashMap<>();
		final ExperienceItem[] items = ExperienceItem.values();
		log.info("Experience items: {}", items);

		for (final ExperienceItem item : items)
		{
			int bankQuantity = 0;
			int seedVaultQuantity = 0;

			if (panel.getBankMap().containsKey(item.getItemID()))
			{
				bankQuantity = panel.getBankMap().get(item.getItemID());
			}
			if (panel.getSeedVaultMap().containsKey(item.getItemID()))
			{
				seedVaultQuantity = panel.getSeedVaultMap().get(item.getItemID());
			}

			final BankedItem banked = new BankedItem(item, panel.getBankMap().getOrDefault(bankQuantity + seedVaultQuantity, 0));

			bankedItemMap.put(item, banked);
			map.put(item.getItemID(), bankQuantity + seedVaultQuantity);
		}
		panel.setBankedItemMap(bankedItemMap);
		log.info("Banked Item Map: {}", panel.getBankedItemMap());
		saveItemBankedMap(panel.getBankedItemMap(), fileNameBankedItemMap);
		panel.setAllOwnedItemsMap(map);
		log.info("All Owned Items Map: {}", panel.getBankedItemMap());
		saveIntIntMap(panel.getAllOwnedItemsMap(), fileNameAllItemsMap);
	}

	private void updateBankMap()
	{
		ItemContainer itemContainer = client.getItemContainer(InventoryID.BANK);
		if (itemContainer == null)
		{
			return;
		}

		panel.getBankMap().clear();
		for (Item item : itemContainer.getItems())
		{
			panel.getBankMap().put(item.getId(), item.getQuantity());
		}

		log.info("Bank Map: {}", panel.getBankMap());
		updateBankedItemMap();
		saveIntIntMap(panel.getBankMap(), fileNameBankMap);
		saveIntIntMap(panel.getAllOwnedItemsMap(), fileNameAllItemsMap);
	}

	private void updateSeedVaultMap()
	{
		final Widget titleContainer = client.getWidget(WidgetInfo.SEED_VAULT_TITLE_CONTAINER);
		if (titleContainer == null)
		{
			return;
		}

		final Widget[] children = titleContainer.getDynamicChildren();
		if (children == null || children.length < 2)
		{
			return;
		}

		final ItemContainer itemContainer = client.getItemContainer(InventoryID.SEED_VAULT);
		if (itemContainer == null)
		{
			return;
		}

		panel.getSeedVaultMap().clear();

		for (Item item : itemContainer.getItems())
		{
			panel.getSeedVaultMap().put(item.getId(), item.getQuantity());
		}

		log.info("Seed Vault Map: {}", panel.getSeedVaultMap());
		updateBankedItemMap();
		saveIntIntMap(panel.getSeedVaultMap(), fileNameSeedVaultMap);
	}

	public boolean saveIntIntMap(Map<Integer, Integer> map, String fileName)
	{
		try
		{
			BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));

			final Gson gson = new GsonBuilder().setPrettyPrinting().create();
			final String json = gson.toJson(map);

			writer.write(json);
			writer.newLine();
			writer.close();

			return true;
		}
		catch (IOException ioe)
		{
			System.out.println("Could not save using buffered writer");
			return false;
		}
	}

	public boolean saveItemBankedMap(Map<ExperienceItem, BankedItem> map, String fileName)
	{
		try
		{
			BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));

			final Gson gson = new GsonBuilder().setPrettyPrinting().create();
			final String json = gson.toJson(map);

			writer.write(json);
			writer.newLine();
			writer.close();

			return true;
		}
		catch (IOException ioe)
		{
			System.out.println("Could not save using buffered writer");
			return false;
		}
	}

	public boolean saveItemBankedMultimap(Multimap<ExperienceItem, BankedItem> multimap, String fileName)
	{
		Map<ExperienceItem, Collection<BankedItem>> map = multimap.asMap();
		try
		{
			BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));

			final Gson gson = new GsonBuilder().setPrettyPrinting().create();
			final String json = gson.toJson(map);

			writer.write(json);
			writer.newLine();
			writer.close();

			return true;
		}
		catch (IOException ioe)
		{
			System.out.println("Could not save using buffered writer");
			return false;
		}
	}

	public boolean loadUsingBufferedWriter(String fileName)
	{
		if (fileName.equals(fileNameBankedItemMap))
		{
			Map<ExperienceItem, BankedItem> map = new HashMap<>();

			Type type = new TypeToken<Map<ExperienceItem, BankedItem>>()
			{
			}.getType();

			try
			{
				final Gson gson = new GsonBuilder().setPrettyPrinting().create();

				BufferedReader br = new BufferedReader(new FileReader(fileName));
				map = gson.fromJson(br, type);
				log.info("Loading Banked Item Map {}: ", map);

				panel.setBankedItemMap(map);

				return true;
			}
			catch (IOException ioe)
			{
				System.out.println("IOException");
				return false;
			}
		}
		else if (fileName.equals(fileNameLinkedMap))
		{
			Map<ExperienceItem, Collection<BankedItem>> map = new HashMap<>();

			Type type = new TypeToken<Map<ExperienceItem, Collection<BankedItem>>>()
			{
			}.getType();

			try
			{
				final Gson gson = new GsonBuilder().setPrettyPrinting().create();

				BufferedReader br = new BufferedReader(new FileReader(fileName));
				map = gson.fromJson(br, type);
				Multimap<ExperienceItem, BankedItem> multimap = ArrayListMultimap.create();
				map.forEach(multimap::putAll);
				log.info("Loading Linked Map {}: ", multimap);

				panel.setLinkedMap(multimap);

				return true;
			}
			catch (IOException ioe)
			{
				System.out.println("IOException");
				return false;
			}
		}
		else
		{
			Map<Integer, Integer> map = new HashMap<>();

			Type type = new TypeToken<Map<Integer, Integer>>()
			{
			}.getType();

			try
			{
				final Gson gson = new GsonBuilder().setPrettyPrinting().create();

				BufferedReader br = new BufferedReader(new FileReader(fileName));
				map = gson.fromJson(br, type);

				if (fileName.equals(fileNameBankMap))
				{
					log.info("Loading Bank Map {}: ", map);
					panel.setBankMap(map);
				}
				else if (fileName.equals(fileNameSeedVaultMap))
				{
					log.info("Loading Seed Vault {}: ", map);
					panel.setSeedVaultMap(map);
				}
				else if (fileName.equals(fileNameAllItemsMap))
				{
					log.info("Loading All Items {}: ", map);
					panel.setAllOwnedItemsMap(map);
				}

				return true;
			}
			catch (IOException ioe)
			{
				System.out.println("IOException");
				return false;
			}
		}
	}

	private void updateConfiguration()
	{

	}

	private void loadConfiguration()
	{

	}
}
