# Politécnico Open World (POW)

**Politécnico Open World (POW)** es una aplicación interactiva de exploración 2D con vista *top-down* para Android. El proyecto integra mapas del mundo real mediante OpenStreetMap (OSM) con un motor propio de renderizado para interiores, permitiendo la transición fluida entre exteriores y el interior de edificios, plazas y salones de clase.

El desarrollo está basado nativamente en **Jetpack Compose** y utiliza una arquitectura **Data-Driven UI** para manejar la escalabilidad de miles de locaciones sin incrementar la cantidad de código fuente.

## ⚙️ Arquitectura y Enfoque Técnico

Para evitar el antipatrón de crear una vista o archivo por cada locación física (Ej. `Salon101.kt`, `Salon102.kt`), el proyecto utiliza un modelo de **Data-Driven UI**:

1.  **Jerarquía basada en Base de Datos:** Las locaciones (Campus -> Edificio -> Piso -> Salón) se almacenan como nodos en una base de datos local (Room).
2.  **Renderizado Dinámico:** Existe un único componente `LocationScreen` genérico. El *ViewModel* consulta la base de datos, obtiene la matriz bidimensional de colisiones/texturas del nodo correspondiente y Compose dibuja la vista dinámicamente.
3.  **Patrón MVVM y Clean Architecture:** * **Presentation:** Jetpack Compose, ViewModels, StateFlow.
    * **Domain:** Lógica pura de juegos, algoritmos de *pathfinding* (A* para navegación) y reglas de vehículos.
    * **Data:** Repositorios, clientes de red (WebSockets) y base de datos local.

## 🚀 Características del Proyecto

* **Mapeo Híbrido (Exterior/Interior):** Uso de `osmdroid` para la navegación en el mundo real y Canvas de Compose para el dibujo de matrices de interiores.
* **Control de Vehículos:** Sistema de movimiento con distintos tipos de transporte y un algoritmo de *snap-to-road* (ajuste a la carretera) leyendo los metadatos de las vías de OSM.
* **Módulos de Juegos Embebidos:** Integración de minijuegos nativos (Pacman, Supervivencia Zombie, Basquetbol) que se ejecutan como eventos dentro de zonas específicas del mapa.
* **Conectividad Multijugador:**
    * **Local:** Integración de la API de Bluetooth de Android para descubrir e interactuar con dispositivos cercanos.
    * **Online:** Comunicación en tiempo real a través de WebSockets utilizando un servidor Node.js.

## 📂 Estructura del Código

El proyecto sigue una estructura modular orientada a características (*Feature-based*), pensada para facilitar un futuro refactor hacia múltiples submódulos de Gradle:

```text
app/src/main/java/ovh/gabrielhuav/pow/
│
├── core/                   # UI genérica (Theme, Type), Navegación, Utilidades compartidas
├── data/                   # Implementación de repositorios, BD Room, DTOs y Hardware (BT, Sockets)
├── domain/                 # Casos de uso, interfaces de repositorios, modelos puros
│
├── features/               # Dominios funcionales de la aplicación
│   ├── map_exterior/       # Lógica y UI de OpenStreetMap y vehículos
│   ├── map_interior/       # Motor de renderizado dinámico de matrices bidimensionales
│   ├── minigames/          # Componentes aislados para Pacman y Zombies
│   └── multiplayer/        # Flujos de conexión y emparejamiento (Lobby)
│
└── MainActivity.kt         # Entry point (Single-Activity Architecture)

---

## 📋 Historial de Cambios

### 2026-05-24 — Elementos Decorativos en el Mapa Exterior

Se añadió un sistema de elementos decorativos puramente visuales al mapa del mundo abierto para darle más vida al entorno. El jugador no puede interactuar con ninguno de estos elementos.

**Archivos nuevos:**
- `features/map_exterior/ui/components/DecorativeElementManager.kt` — Singleton que gestiona la generación y renderizado de los elementos.

**Archivos modificados:**
- `features/map_exterior/ui/WorldMapScreen.kt` — Bloque de render añadido en la capa OSM nativa.
- `res/values/ids.xml` — Añadido `decorative_cache_tag` para persistir el estado del caché en el `MapView`.

**Detalles técnicos:**
- Se definen 7 tipos de elementos con pesos de aparición: 🌳 Árbol (30 %), 🌿 Arbusto (25 %), 🌸 Flor (20 %), 🪨 Roca (15 %), 🐿️ Ardilla (4 %), 🐦 Pájaro (4 %), 🐈 Gato (2 %).
- La posición de cada elemento se genera con un `Random` sembrado por coordenadas de celda (0.001° × 0.001° ≈ 111 m), garantizando que los mismos elementos aparezcan siempre en los mismos lugares del mundo.
- Se muestran un máximo de 50 elementos a la vez (los más cercanos al jugador). La lista se regenera únicamente cuando el jugador se desplaza más de ~150 m desde la última generación, sin costo adicional por frame.
- Los elementos usan el mismo patrón de reciclado de `Marker` que los NPCs (sin crear objetos nuevos en cada frame) y se hacen invisibles al alejar el zoom por debajo de 16.5, idéntico al umbral de los NPCs.
- Solo aplica al proveedor de mapas **OSM nativo**; la rama WebView (CartoDB, ESRI, etc.) no se ve afectada.

---

### 2026-05-29 — Refactor: Extracción de `RoadNetworkIndex` desde `WorldMapViewModel`

Se extrajo toda la lógica de consulta espacial de la red vial a una clase dedicada para reducir el tamaño de `WorldMapViewModel` y mejorar la cohesión.

**Archivos nuevos:**
- `features/map_exterior/viewmodel/RoadNetworkIndex.kt` — Encapsula el índice espacial de segmentos de calles y el grid de nodos para consultas eficientes de proximidad y ruteo.

**Archivos modificados:**
- `features/map_exterior/viewmodel/WorldMapViewModel.kt` — Reducido de 1 646 a 1 508 líneas (−138). Todas las llamadas al índice espacial se delegan a `private val roadIndex = RoadNetworkIndex()`.

**Qué contiene `RoadNetworkIndex`:**
- Clase interna `Seg` (representación de segmentos de vía con bounding-box precalculado).
- Índice espacial de doble grid: `segGrid` para snap-to-road y `nodeGrid` para ruteo.
- `rebuild(network)` — reconstrucción eager del índice al cargar nueva red vial.
- `getNearestPoint(t, network)` — proyección del punto más cercano sobre la red (con reconstrucción lazy si el network cambia).
- `calculateRoute(from, to, network)` — cálculo de ruta greedy sobre nodos de la red.
- `distance(a, b)` — distancia euclídea en grados (uso interno y en lógica de combate/interacción del ViewModel).

**Detalles técnicos:**
- La reconstrucción del índice es lazy mediante comparación de referencia (`===`), manteniendo el comportamiento original de `ensureIndex`.
- Sin dependencias de Android ni de `StateFlow`; la clase es puramente algorítmica y testeable de forma aislada.
- Los imports `kotlin.math.{floor, max, min, pow, sqrt}` se eliminaron del ViewModel al quedar exclusivos de `RoadNetworkIndex`.