package ai.cogmission.fxmaps.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.web.WebView;
import javafx.stage.PopupWindow.AnchorLocation;
import javafx.stage.Window;
import netscape.javascript.JSObject;
import ai.cogmission.fxmaps.event.MapEventHandler;
import ai.cogmission.fxmaps.event.MapEventType;
import ai.cogmission.fxmaps.event.MapInitializedListener;
import ai.cogmission.fxmaps.event.MapReadyListener;
import ai.cogmission.fxmaps.model.DirectionsRoute;
import ai.cogmission.fxmaps.model.LatLon;
import ai.cogmission.fxmaps.model.Location;
import ai.cogmission.fxmaps.model.Locator;
import ai.cogmission.fxmaps.model.MapObject;
import ai.cogmission.fxmaps.model.MapOptions;
import ai.cogmission.fxmaps.model.MapShape;
import ai.cogmission.fxmaps.model.MapShapeOptions;
import ai.cogmission.fxmaps.model.MapStore;
import ai.cogmission.fxmaps.model.MapType;
import ai.cogmission.fxmaps.model.Marker;
import ai.cogmission.fxmaps.model.MarkerOptions;
import ai.cogmission.fxmaps.model.MarkerType;
import ai.cogmission.fxmaps.model.PersistentMap;
import ai.cogmission.fxmaps.model.Polyline;
import ai.cogmission.fxmaps.model.PolylineOptions;
import ai.cogmission.fxmaps.model.Route;
import ai.cogmission.fxmaps.model.Waypoint;

import com.lynden.gmapsfx.GoogleMapView;
import com.lynden.gmapsfx.javascript.object.GoogleMap;
import com.lynden.gmapsfx.javascript.object.LatLong;

/**
 * Undecorated {@link Pane} extension which is specialized to contain a 
 * map view and an optional {@link DirectionsPane}.
 * <p>
 * To create a MapPane:
 * <br><br>
 * 1. Map map = Map.create("My Map Name");
 * <br>--or--<br>
 * 2. MapPane map = Map.create("My Map Name");
 * <br><br>
 * 
 * To call JavaFX Node methods on the {@link MapPane} (such as setting width and height), use option 2.
 * 
 * @author cogmission
 *
 */
public class MapPane extends StackPane implements Map {
    private static final String DEFAULT_OVERLAY_MESSAGE = 
        "Click \"Create / Select Map\" To:\n\n" +
        "\t" + '\u2022' + " Create a new map\n\n \t\t-- or -- \n\n" + 
        "\t" + '\u2022' + " Select a loaded map\n\n \t\t-- or -- \n\n" + 
        "\t" + '\u2022' + " Load a GPX File";
    
    private BorderPane contentPane = new BorderPane();
    
    private static final MapOptions DEFAULT_MAP_OPTIONS = getDefaultMapOptions();
    private final MapEventHandler DEFAULT_MAPEVENT_HANDLER = getDefaultMapEventHandler();
    private MapStore MAP_STORE;
    private boolean defaultMapEventHandlerInstalled = true;
    
    private PolylineOptions DEFAULT_POLYLINE_OPTIONS;
    
    
    protected GoogleMapView mapComponent;
    protected GoogleMap googleMap;
    
    protected MapOptions userMapOptions;
    
    protected DirectionsPane directionsPane;
    
    protected Route currentRoute;
    
    protected List<MapReadyListener> readyListeners = new ArrayList<>();
    
    protected boolean overlayVisible;
    
    protected ContextMenu contextMenu;
    protected MapObject currMapObj;
   
    protected StackPane dimmer;
    protected Label dimmerMessage;
    
    protected Map.Mode currentMode = Map.Mode.NORMAL;
    
    
    /**
     * Constructs a new {@code MapPane}
     */
    MapPane() {
        setPrefWidth(1000);
        setPrefHeight(780);
        
        getChildren().add(contentPane);
        
        mapComponent = new GoogleMapView(); 
        contentPane.setCenter(mapComponent);
        
        contextMenu = getContextMenu();
        contextMenu.hideOnEscapeProperty().set(true);
        
        mapComponent.getWebView().setOnMousePressed(e -> {
            if(e.isPrimaryButtonDown()) {
                contextMenu.hide();
                refresh();
            }
        });
        
        directionsPane = new DirectionsPane();
        directionsPane.setPrefWidth(200);
        setDirectionsVisible(false);
        
        
    }
    
    public ContextMenu getContextMenu() {
        if(contextMenu == null) {
            ContextMenu menu = new ContextMenu();
            MenuItem deleteItem = new MenuItem("Delete Object...");
            deleteItem.setOnAction(e -> {
                if(currMapObj instanceof Waypoint){
                    Waypoint wp = ((Waypoint)currMapObj);
                    Route editedRoute = getRouteForWaypoint(wp);
                    eraseRoute(editedRoute);
                    removeWaypoint(wp);
                    displayRoute(editedRoute);
                    MAP_STORE.store();
                }else if((currMapObj instanceof MapShape)) {
                    Polyline p = (Polyline)currMapObj;
                    Route editedRoute = getRouteForLine(p);
                    Waypoint editedWaypoint = getWaypointForLine(editedRoute, p);
                    eraseRoute(editedRoute);
                    removeWaypoint(editedWaypoint);
                    displayRoute(editedRoute);
                    MAP_STORE.store();
                }
            });
            menu.getItems().add(deleteItem);
            
            menu.setAnchorLocation(AnchorLocation.WINDOW_TOP_LEFT);
            
            contextMenu = menu;
        }
        
        return contextMenu;
    }
    
    /**
     * Creates and initializes child components to prepare the map
     * for immediate use. when the {@link Map} is initialized it is 
     * given two call backs which when called, indicate that the map
     * is ready for use. 
     * 
     * Before this method is called, any desired {@link MapOptions} must
     * be set on the map before calling {@code #initialize()} 
     */
    @Override
    public void initialize() {
        // first complete ui initialization
        configureOverlay();
        
        mapComponent.addMapInializedListener(this);
    }
    
    @Override
    public void addMap(String mapName) {
        if(mapName == null) return;
        
        MAP_STORE.addMap(mapName);
        MAP_STORE.getMap(mapName).setMapOptions(DEFAULT_MAP_OPTIONS);
    }
    
    /**
     * Returns the {@link MapOptions} set on this {@code Map}
     * 
     * @return  this {@code Map}'s {@link MapOptions}
     */
    public MapOptions getMapOptions() {
        return this.userMapOptions;
    }
    
    /**
     * Specifies the {@link MapOptions} to use. <em>Note</em> this must
     * be set prior to calling {@link #initialize()}
     * 
     * @param mapOptions    the {@code MapOptions} to use.
     */
    @Override
    public void setMapOptions(MapOptions options) {
        this.userMapOptions = options;
    }
    
    /**
     * Returns this map's persistent store.
     */
    @Override
    public MapStore getMapStore() {
        return MAP_STORE;
    }
    
    /**
     * Sets the map mode to one of {@link Mode}
     * @param mode  the mode to set
     */
    public void setMode(Mode mode) {
        currentMode = mode;
        
        if(mode == Mode.ADD_WAYPOINTS) {
            setBorder(new Border(new BorderStroke(Color.GREEN, BorderStrokeStyle.SOLID, null, new BorderWidths(5))));
        }else{
            setBorder(null);
        }
    }
    
    /**
     * Makes the right {@link DirectionsPane} visible or invisible.
     * @param b
     */
    @Override
    public void setDirectionsVisible(boolean b) {
        if(b) {
            if(contentPane.getRight() == null) {
                contentPane.setRight(directionsPane);
            }
        }else{
            contentPane.setRight(null);
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void mapInitialized() {
        
        mapComponent.addMapReadyListener(() -> {
            // This call will fail unless the map is completely ready.
            //
            // NOTE: Leaving this in for documentation on how to go from lat/lon
            // to pixel coordinates.
            //checkCenter(center);
            
            // Locates the user's aprox. location and centers the map there.
            try {
                centerMapOnLocal();
                
                MAP_STORE = MapStore.load(MapStore.DEFAULT_STORE_PATH);
                
                DEFAULT_POLYLINE_OPTIONS = getDefaultPolylineOptions();
                
                for(MapReadyListener li : readyListeners) {
                    li.mapReady();
                }
            }catch(Exception e) {
                e.printStackTrace();
            }
        });
        
        createGoogleMap();
        
        /** See {@link #removeDefaultMapEventHandler()} */
        if(defaultMapEventHandlerInstalled) {
            addMapEventHandler(MapEventType.CLICK, DEFAULT_MAPEVENT_HANDLER);
        }
    }
    
    /**
     * Removes the default handler which:
     * <ol>
     *     <li>Checks to see if "routeSimulationMode" is true (see {@link #setRouteSimulationMode(boolean)})
     *     <li> if routeSimulationMode is true, and the user left-clicked the map, a Waypoint will be added
     *     to either a route named "temp" or if {@link #setCurrentRoute} has been called with a valid Route, 
     *     it will be added to that current {@link Route}
     *     <li> if routeSimulationMode is false, nothing happens.
     * </ol>
     * 
     * <em>WARNING: Must be called before {@link Map#initialize()} is called or 
     * else this has no effect</em>
     */
    @Override
    public void removeDefaultMapEventHandler() {
        defaultMapEventHandlerInstalled = false;
    }
    
    /**
     * Sets the current {@link Route} to which {@link #addNewWaypoint(Waypoint)} will add a waypoint.
     * Routes may be created by calling {@link Map#createRoute(String)} with a unique name.
     * 
     * @param r        the {@code Route} make current.
     */
    @Override
    public void setCurrentRoute(Route route) {
        this.currentRoute = route;
    }
    
    /**
     * Returns the current {@link Route} to which {@link #addNewWaypoint(Waypoint)} will add a waypoint.
     * Routes may be created by calling {@link Map#createRoute(String)} with a unique name.
     * 
     * @returns the {@code Route} which is current current.
     */
    @Override
    public Route getCurrentRoute() {
        return currentRoute;
    }
    
    /**
     * Adds a {@link Node} acting as a toolbar
     * @param n a toolbar
     */
    public void addToolBar(Node n) {
        contentPane.setTop(n);
    }
    
    /**
     * Returns a mutable {@link IntegerProperty} used to display
     * and change the zoom factor.
     * 
     * @return zoom {@link IntegerProperty}
     */
    @Override
    public IntegerProperty zoomProperty() {
        return googleMap.zoomProperty();
    }
    
    /**
     * Demonstrates how to go from lat/lon to pixel coordinates.
     * @param center
     */
    @SuppressWarnings("unused")
    private void checkCenter(LatLon center) {
        System.out.println("Testing fromLatLngToPoint using: " + center);
        Point2D p = googleMap.fromLatLngToPoint(center.toLatLong());
        System.out.println("Testing fromLatLngToPoint result: " + p);
        System.out.println("Testing fromLatLngToPoint expected: " + mapComponent.getWidth()/2 + ", " + mapComponent.getHeight()/2);
        System.out.println("type = "+ MarkerType.BROWN.iconPath());
    }

    @Override
    public void centerMapOnLocal() {
        try {
            String ip = Locator.getIp();
            Location l = Locator.getIPLocation(ip);
            googleMap.setCenter(new LatLong(l.getLatitude(), l.getLongitude()));
        }catch(Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Sets the center location of the map to the specified lat/lon 
     * coordinates.
     * 
     * @param ll    the lat/lon coordinates around which to center the map.
     */
    public void setCenter(LatLon ll) {
        googleMap.setCenter(ll.toLatLong());
    }

    @Override
    public void addMapInializedListener(MapInitializedListener listener) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void removeMapInitializedListener(MapInitializedListener listener) {
        // TODO Auto-generated method stub
        
    }

    /**
     * Adds the specified {@link MapReadyListener} to the list of listeners
     * notified when the map becomes fully engageable.
     * 
     * @param listener  the {@code MapReadyListener} to add
     */
    @Override
    public void addMapReadyListener(MapReadyListener listener) {
        if(!readyListeners.contains(listener)) {
            readyListeners.add(listener);
        }
    }

    @Override
    public void removeReadyListener(MapReadyListener listener) {
        // TODO Auto-generated method stub
        
    }
    
    /**
     * Displays a {@link Marker} on the {@code Map}, as opposed to adding
     * a {@link Waypoint} which adds a {@code Marker} and a connecting
     * line from any previous {@link Marker} along a given {@link Route}.
     * 
     * @param marker    the marker to add
     * @see Wayoint
     */
    @Override
    public void displayMarker(Marker marker) {
        googleMap.addMarker(marker.convert());
        String name = marker.convert().getVariableName();
        System.out.println("marker name = " + name);
        Object obj = googleMap.getJSObject().getMember(name);
        System.out.println("marker obj = " + obj);
        System.out.println("icon = " + marker.convert().getJSObject().getMember("icon"));
    }
    
    private void setCurrentMapObject(MapObject o) {
        this.currMapObj = o;
    }
    
    /**
     * Removes the specified {@link Marker} from the {@code Map}, as opposed to removing
     * a {@link Waypoint} which removes a {@code Marker} and a connecting
     * line from any previous {@link Marker} along a given {@link Route}. 
     * 
     * This method only removes the marker from the display, it does nothing to the route.
     * 
     * @param marker    the marker to remove
     * @see Waypoint
     */
    @Override
    public void eraseMarker(Marker marker) {
        googleMap.removeMarker(marker.convert());
    }

    /**
     * Creates a {@link Waypoint} which is a combination of a 
     * {@link LatLon} and a {@link Marker}. 
     * 
     * @param latLon    the latitude/longitude position of the waypoint 
     * @return  the newly created {@code Waypoint}
     */
    @Override
    public Waypoint createWaypoint(LatLon latLon) {
        MarkerOptions opts = new MarkerOptions()
            .position(latLon)
            .title("Waypoint")
            .icon(MarkerType.GREEN.nextPath())
            .visible(true);
        
        return new Waypoint(latLon, new Marker(opts));
    }

    /**
     * Adds a {@link Waypoint} to the map connecting it to any 
     * previously added {@code Waypoint}s by a connecting line,
     * as opposed to adding a {@link Marker} which doesn't add 
     * a line. The specified Waypoint is also added to the 
     * currently focused route.
     * 
     * @param waypoint  the {@link Waypoint} to be added.
     * @see #displayMarker(Marker)
     */
    @Override
    public void addNewWaypoint(Waypoint waypoint) {
        displayWaypoint(waypoint);
        
        currentRoute.addWaypoint(waypoint);
        if(currentRoute.size() > 1) {
            Polyline poly = connectLastWaypoint(waypoint, null);
            displayShape(poly);
        }
        
        MAP_STORE.store();
    }
    
    /**
     * Adds a {@link Waypoint} to the map connecting it to any 
     * previously added {@code Waypoint}s by a connecting line,
     * as opposed to adding a {@link Marker} which doesn't add 
     * a line. The specified Waypoint is also added to the 
     * currently focused route.
     * 
     * @param waypoint          the {@link Waypoint} to be added.
     * @param polylineOptions   the subclass of {@link MapShapeOptions} containing desired
     *                  properties of the rendering operation.
     * @see #displayMarker(Marker)
     * @see #addNewWaypoint(Waypoint)
     */
    @Override
    public <T extends MapShapeOptions<T>>void addNewWaypoint(Waypoint waypoint, T polylineOptions) {
        currentRoute.addWaypoint(waypoint);
        displayWaypoint(waypoint);
        
        if(currentRoute.size() > 1) {
            Polyline poly = connectLastWaypoint(waypoint, polylineOptions);
            displayShape(poly);
        }
        
        MAP_STORE.store();
     }
    
    /**
     * Adds a {@link Waypoint} from the {@link MapStore} when the specified
     * Waypoint is already part of a {@link Route} and already has connecting leg lines.
     * @param waypoint     the Waypoint to add
     */
    @Override
    public void displayWaypoint(Waypoint waypoint) {
        displayMarker(waypoint.getMarker());
        
        addObjectEventHandler(waypoint.getMarker(), MapEventType.RIGHTCLICK, (JSObject o) -> {
            setCurrentMapObject(waypoint);
            setCurrentRoute(getRouteForWaypoint(waypoint));
            
            String id = waypoint.getMarker().getMarkerOptions().getIcon();
            id = id.substring(id.lastIndexOf("M"), id.lastIndexOf("."));
            contextMenu.getItems().get(0).setText("Clear " + id);
            
            LatLong cxtLL = new LatLong((JSObject) o.getMember("latLng"));
            Point2D p = googleMap.fromLatLngToPoint(cxtLL);
            Window w = MapPane.this.getScene().getWindow();
            contextMenu.show(
                mapComponent.getWebView(),
                    w.getX() + p.getX() + 10, 
                        w.getY() + p.getY());
        });
    }
    
    /**
     * Returns the {@link Polyline} which connects the specified {@link Waypoint}
     * @param   lastWaypoint        the newly added waypoint
     * @param   polylineOptions     the line options to use for rendering
     * @return  the connecting Polyline
     */
    private <T extends MapShapeOptions<T>> Polyline connectLastWaypoint(Waypoint lastWaypoint, T polylineOptions) {
        List<LatLon> l = new ArrayList<>();
        l.add(currentRoute.getWaypoint(currentRoute.size() - 2).getLatLon());
        l.add(currentRoute.getWaypoint(currentRoute.size() - 1).getLatLon());
        
        Polyline poly = new Polyline(polylineOptions == null ? 
            PolylineOptions.copy(DEFAULT_POLYLINE_OPTIONS).path(l) : 
                (PolylineOptions)polylineOptions);
     
        lastWaypoint.setConnection(poly);
        
        currentRoute.addLine(poly);
        return poly;
    }

    /**
     * Removes the {@link Waypoint} from the map and its connecting line.
     * @param waypoint
     * @see #displayMarker(Marker)
     */
    @Override
    public void removeWaypoint(Waypoint waypoint) {
        currentRoute.removeWaypoint(waypoint);
    }
    
    /**
     * Adds the specified {@link MapShape} to this {@code Map}
     * 
     * @param shape     the {@code MapShape} to add
     */
    @Override
    public void displayShape(MapShape shape) {
        addLineMouseListener(getWaypointForLine(currentRoute, (Polyline)shape), (Polyline)shape);
        googleMap.addMapShape(shape.convert());
    }
    
    /**
     * Removes the specified {@link MapShape} from this {@code Map}
     * @param shape     the {@code MapShape} to remove
     */
    @Override
    public void eraseShape(MapShape shape) {
        googleMap.removeMapShape(shape.convert());
    }
    
    /**
     * Adds a {@link Route} to this {@code Map}
     * @param route     the route to add
     */
    @Override
    public void addRoute(Route route) {
        PersistentMap currentMap = MAP_STORE.getMap(MAP_STORE.getSelectedMapName());
        if(currentMap.getRoute(route.getName()) == null) {
            MAP_STORE.getMap(MAP_STORE.getSelectedMapName()).addRoute(route);
            MAP_STORE.store();
        }
    }
    
    /**
     * Removes the specified {@link Route} from this {@code Map}
     * 
     * @param route     the route to remove
     */
    @Override
    public void removeRoute(Route route) {
        MAP_STORE.getMap(MAP_STORE.getSelectedMapName()).removeRoute(route);
        MAP_STORE.store();
    }
    
    /**
     * Clears the specified {@link Route} of all its contents
     * (i.e. Lines and Markers)
     * 
     * @param route     the route to be cleared
     */
    @Override
    public void clearRoute(Route route) {
        eraseRoute(route);
        
        route.removeAllWaypoints();
        
        MAP_STORE.store();
    }
    
    /**
     * Non-destructive clearing of all map objects. The {@code Map}
     * and all its {@link Route}s will still contain their content,
     * but the display will be cleared.
     * 
     * @param   route   the {@link Route} to erase
     */
    public void eraseRoute(Route route) {
        for(Waypoint w : route.getWaypoints()) {
            googleMap.removeMarker(w.getMarker().convert());
        }
        for(Polyline line : route.getLines()) {
            googleMap.removeMapShape(line.convert());
        } 
    }
    
    /**
     * Returns the {@link Route} with the specified name.
     * @param name  the name of the route to return
     * @return  the specified route
     */
    @Override
    public Route getRoute(String name) {
        return MAP_STORE.getMap(MAP_STORE.getSelectedMapName()).getRoute(name);
    }
    
    /**
     * Selects the specified {@link Route}, designating it
     * to be the currently focused route.
     * 
     * @param route the {@code Route} to select.
     */
    @Override
    public void selectRoute(Route route) {
        currentRoute = route;
    }
    
    /**
     * Displays the list of {@link Route}s on this {@code Map}
     * 
     * @param routes    the list of routes to display
     */
    @Override
    public void displayRoutes(List<Route> routes) {
        for(Route r : routes) {
            currentRoute = r;
            displayRoute(r);
        }
        
        refresh();
    }
    
    /**
     * Displays the specified {@link Route} on the map
     * 
     * @param route the route to display
     */
    @Override
    public void displayRoute(Route route) {
        for(Waypoint wp : route.getWaypoints()) {
            if(route.getInterimMarkersVisible() || (!route.getInterimMarkersVisible() && 
                (wp.equals(route.getOrigin()) || wp.equals(route.getDestination())))) {
                
                displayWaypoint(wp);
            }
        }
        
        for(Polyline p : route.getLines()) {
            Waypoint wp = getWaypointForLine(route, p);
            wp.setConnection(p);
            
            displayShape(p);
        }
    }
    
    /**
     * Adds the listener that invokes the context menu for lines.
     * 
     * @param wp    the {@link Waypoint} which owns the specified {@link Polyline}
     * @param p     the Polyline to add the listener to.
     */
    private void addLineMouseListener(Waypoint wp, Polyline p) {
        if(wp != null) {
            System.out.println("Adding listener for waypoint: " + wp.getMarker().getMarkerOptions().getIcon());
            
            addObjectEventHandler((MapObject)p, MapEventType.RIGHTCLICK, (JSObject o) -> {
                setCurrentMapObject(p);
                setCurrentRoute(getRouteForLine((Polyline)p));
                
                String id = wp.getMarker().getMarkerOptions().getIcon();
                id = id.substring(id.lastIndexOf("M"), id.lastIndexOf("."));
                id = id.substring(0, id.length() - 1) + " " + id.substring(id.length() - 1);
                contextMenu.getItems().get(0).setText("Clear \"" + id + "\"'s connection");
                
                LatLong cxtLL = new LatLong((JSObject) o.getMember("latLng"));
                Point2D pt = googleMap.fromLatLngToPoint(cxtLL);
                Window w = MapPane.this.getScene().getWindow();
                contextMenu.show(
                    mapComponent.getWebView(),
                        w.getX() + pt.getX() + 10, 
                            w.getY() + pt.getY());
            });
        }
    }
    
    /**
     * <p>
     * Finds the Waypoint with the same path as the specified line's path.
     * There are copies of Waypoints and Lines which are equal but do not 
     * have the same underlying peer (JSObject). Therefore, picking lines
     * on a map which aren't really displayed will result in context menu 
     * actions that don't invoke anything. 
     * </p><p>
     * This method ensures that we reference the Waypoint and Polyline 
     * which are in the {@link Route}'s list of waypoint and lines which 
     * are the ones that are rendered.
     * </p>
     * @param route     The owning Route
     * @param line      the Polyline to be rendered
     * @return  the owning {@link Waypoint}
     */
    @Override
    public Waypoint getWaypointForLine(Route route, Polyline line) {
        for(Waypoint wp : route.getWaypoints()) {
            if(wp.getConnection() != null && wp.getConnection().getOptions().getPath().equals(line.getOptions().getPath())) {
                return wp;
            }
        }
        return null;
    }
    
    /**
     * Returns the {@link Polyline} which is the line really rendered 
     * for the specified {@link Waypoint}'s connection.
     * 
     * @param route     The owning Route
     * @param wp        the Waypoint to be rendered
     * @return
     */
    @Override
    public Polyline getLineForWaypoint(Route route, Waypoint wp) {
        for(Polyline line : route.getLines()) {
            if(wp.getConnection() != null && wp.getConnection().getOptions().getPath().equals(line.getOptions().getPath())) {
                return line;
            }
        }
        return null;
    }
    
    /**
     * Returns the {@link Route} which contains the specified {@link Waypoint}
     * @param wp    the Waypoint whose owning Route will be returned
     * @return      the Route which contains the specified Waypoint
     */
    @Override
    public Route getRouteForWaypoint(Waypoint wp) {
       Optional<Route> or = Optional.of(MAP_STORE.getMap(MAP_STORE.getSelectedMapName()).getRoutes().stream()
           .filter(r -> r.getWaypoints().stream().anyMatch(w -> w.getLatLon().equals(wp.getLatLon())))
           .collect(Collectors.toList()).get(0));
       return or.isPresent() ? or.get() : null;
    }
    
    /**
     * Returns the {@link Route} which contains the specified {@link Polyline}
     * @param p    the Polyline whose owning Route will be returned
     * @return      the Route which contains the specified Polyline
     */
    @Override
    public Route getRouteForLine(Polyline p) {
        Optional<Route> or = Optional.of(MAP_STORE.getMap(MAP_STORE.getSelectedMapName()).getRoutes().stream()
            .filter(r -> r.getLines().stream().anyMatch(l -> l.getOptions().getPath().equals(p.getOptions().getPath())))
            .collect(Collectors.toList()).get(0));
        return or.isPresent() ? or.get() : null;
    }
    
    /**
     * Redraws the map
     */
    @Override
    public void refresh() {
        googleMap.setZoom(googleMap.getZoom() + 1);
        googleMap.setZoom(googleMap.getZoom() - 1);
    }
    
    /**
     * Removes all displayed {@link Route}s from this {@code Map}
     */
    @Override
    public void clearMap() {
        currentRoute = null;
        
        if(MAP_STORE.getSelectedMapName() == null || MAP_STORE.getMap(MAP_STORE.getSelectedMapName()) == null) {
            return;
        }
        
        clearMap(MAP_STORE.getSelectedMapName());
    }
    
    /**
     * Removes all displayed {@link Route}s from the map specified by mapName
     * 
     * @param mapName   the name of the map to remove
     */
    public void clearMap(String mapName) {
        for(Route r : MAP_STORE.getMap(MAP_STORE.getSelectedMapName()).getRoutes()) {
            clearRoute(r);
        }
    }
    
    /**
     * Non-destructively erases all displayed content from the map
     * display
     */
    public void eraseMap() {
        if(MAP_STORE.getMap(MAP_STORE.getSelectedMapName()) == null) {
            return;
        }
        for(Route r : MAP_STORE.getMap(MAP_STORE.getSelectedMapName()).getRoutes()) {
            eraseRoute(r);
        }
    }
    
    /**
     * Deletes the currently selected map and its persistent storage.
     * 
     * @param  mapName  the name of the map to delete
     */
    public void deleteMap(String mapName) {
        MAP_STORE.deleteMap(mapName);
        MAP_STORE.store();
    }
    
    /**
     * Removes all {@link Waypoint}s and {@link MapObject}s (Lines) 
     * from the {@link Route} specified by name.
     * 
     * @param name  name of the {@link Route} to clear.
     */
    public void clearRoute(String name) {
        for(Route r : MAP_STORE.getMap(MAP_STORE.getSelectedMapName()).getRoutes()) {
            if(r.getName().equals(name)) {
                clearRoute(r);                
                break;
            }
        }
    }

    @Override
    public DirectionsRoute getSnappedDirectionsRoute(Route route) {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Adds an EventHandler which can be notified of JavaScript events 
     * arising from the map within a given {@link WebView}
     * 
     * @param eventType     the Event Type to monitor
     * @param handler       the handler to be notified.
     */
    @Override
    public void addMapEventHandler(MapEventType eventType, MapEventHandler handler) {
        googleMap.addUIEventHandler(eventType.convert(), handler);
    }
    
    /**
     * Adds an EventHandler which can be notified of JavaScript events 
     * arising from the map object within a given {@link WebView}
     * 
     * @param mapObject     the {@link MapObject} event source
     * @param eventType     the Event Type to monitor
     * @param handler       the handler to be notified.
     */
    @Override
    public void addObjectEventHandler(MapObject mapObject, MapEventType eventType, MapEventHandler handler) {
        googleMap.addUIEventHandler(mapObject.convert(), eventType.convert(), handler);
    }
    
    @Override
    public void addMapObject(MapObject mapObject) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public BooleanProperty routeSnappedProperty() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ObjectProperty<ObservableValue<MapType>> mapTypeProperty() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ObjectProperty<ObservableValue<LatLon>> clickProperty() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ObjectProperty<ObservableValue<LatLon>> centerMapProperty() {
        // TODO Auto-generated method stub
        return null;
    }
    
    /**
     * Called internally to configure size, position and style of the overlay.
     */
    private void configureOverlay() {
        dimmer = new StackPane();
        dimmer.setManaged(false);
        dimmerMessage = new Label(DEFAULT_OVERLAY_MESSAGE);
        dimmerMessage.setFont(Font.font(dimmerMessage.getFont().getFamily(), FontWeight.BOLD, 18));
        dimmerMessage.setTextFill(Color.WHITE);
        dimmer.getChildren().add(dimmerMessage);
        dimmer.setStyle("-fx-background-color: rgba(0, 0, 0, 0.6);");
        getChildren().add(dimmer);
        
        layoutBoundsProperty().addListener((v, o, n) -> {
            Platform.runLater(() -> {
                if(MapPane.this.getScene().getWindow() == null) return;
                Point2D mapPoint = contentPane.localToParent(0, 0);
                double topHeight = contentPane.getTop() == null ? 0 : contentPane.getTop().getLayoutBounds().getHeight();
                dimmer.resizeRelocate(mapPoint.getX(), mapPoint.getY() + topHeight, 
                    contentPane.getWidth(), contentPane.getHeight() - topHeight);
            });
        });
    }
    
    /**
     * Returns the flag which indicates whether the overlay is currently visible
     * or not.
     * @return  true if visible, false if not
     */
    public boolean isOverlayVisible() {
        return overlayVisible;
    }
    
    /**
     * Sets the flag which indicates whether the overlay is currently visible
     * or not.
     * @param b     true if visible, false if not
     */
    public void setOverlayVisible(boolean b) {
        dimmer.setVisible(overlayVisible = b);
    }
    
    /**
     * Sets the overlay message which is the message displayed when 
     * {@link #setOverlayVisible(boolean)} is called with "true".
     * @param message   the message to be displayed.
     */
    public void setOverlayMessage(String message) {
        dimmerMessage.setText(message);
    }
    
    /**
     * Sets the string used to set the style of the overlay.
     * 
     * @param fxmlStyleString   Style String such as:<br> 
     *                          <b>"-fx-background-color: rgba(0,0,0,0.6);
     *                          </b></em>(the default)</em>"
     */
    public void setOverlayStyle(String fxmlStyleString) {
        dimmer.setStyle(fxmlStyleString);
    }
    
    /**
     * Returns this JavaFX {@link Node} 
     */
    @Override
    public MapPane getNode() {
        return this;
    }
    
    /**
     * Return some default {@link PolylineOptions}
     * 
     * @return      the constructed options
     */
    public static PolylineOptions getDefaultPolylineOptions() {
        return new PolylineOptions()
            .strokeColor("red")
            .visible(true)
            .clickable(true)
            .strokeWeight(2);
    }
    
    /**
     * Returns the default {@link MapOptions}
     * @return  the default MapOptions
     */
    public static MapOptions getDefaultMapOptions() {
        MapOptions options = new MapOptions();
        options.mapMarker(true)
            .zoom(15)
            .overviewMapControl(false)
            .panControl(false)
            .rotateControl(false)
            .scaleControl(false)
            .streetViewControl(false)
            .zoomControl(false)
            .mapTypeControl(false)
            .mapType(MapType.ROADMAP);
        
        return options;
    }
    
    /**
     * Returns the default {@link MapEventHandler}. This is the handler that
     * converts clicks on the map to {@link Waypoint}s added.
     * 
     * @return the default {@code MapEventHandler}
     */
    private MapEventHandler getDefaultMapEventHandler() {
        return (JSObject obj) -> {
            if(currentMode == Mode.ADD_WAYPOINTS) {
                LatLong ll = new LatLong((JSObject) obj.getMember("latLng"));
                
                Waypoint waypoint = createWaypoint(new LatLon(ll.getLatitude(), ll.getLongitude()));
                
                addNewWaypoint(waypoint);
                
                System.out.println("clicked: " + ll.getLatitude() + ", " + ll.getLongitude());
            }
        };
    }
    
    /**
     * Creates the internal {@link GoogleMap} object
     */
    private void createGoogleMap() {
        googleMap = mapComponent.createMap(userMapOptions == null ? 
            DEFAULT_MAP_OPTIONS.convert() : userMapOptions.convert());
    }
}
