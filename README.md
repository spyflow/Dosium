# Dosium (Minecraft 1.20.4 • Fabric • GPU-Driven Vulkan + UMA Compute)

<p align="left">
  <img src="https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f8/Vulkan_API_logo.svg/960px-Vulkan_API_logo.svg.png" width="96" alt="Vulkan API">
</p>

[![GitHub Repository](https://img.shields.io/badge/GitHub-spyflow%2Fdosium-181717?logo=github)](https://github.com/spyflow/dosium)
[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.4-success.svg)](https://fabricmc.net/)

> **Aviso de Afiliación y Obra Derivada:**  
> **Dosium** es un proyecto independiente de código abierto licenciado bajo **GNU LGPL-3.0-only** y **no está afiliado oficialmente** con CaffeineMC (Sodium) ni con xCollateral (VulkanMod). Es una obra derivada que unifica y extiende el código de [Sodium](https://github.com/CaffeineMC/sodium-fabric) y [VulkanMod](https://github.com/xCollateral/VulkanMod) añadiendo un pipeline de cómputo paralelo (**GPU-Driven Rendering, Compute Shaders & UMA Zero-Copy**).

El objetivo de **Dosium** es eliminar los cuellos de botella restantes en la CPU (recorrido de secciones, empaquetado/construcción de mallas, transferencias de memoria por *staging buffers* y generación de ruido 3D), manteniendo un único menú de opciones unificado y optimizando el reparto de recursos en **APUs e iGPUs con arquitectura de memoria unificada (UMA)**, así como en GPUs dedicadas.

---

## 1. De Sodium+Vulkan a Dosium: Qué cambia exactamente

| Tarea del Motor | En Sodium+Vulkan (Base) | En Dosium (GPU-Driven + UMA) |
| :--- | :--- | :--- |
| **Inicialización y Swapchain** | Backend base de VulkanMod | **Se conserva** (con exposición de cola `VK_QUEUE_COMPUTE_BIT` y *timestamps*) |
| **Gestión de Memoria (APUs)** | Limitado al *carveout* BIOS (ej. 64 MB) + Staging | **UMA Zero-Copy (`HOST_VISIBLE \| HOST_COHERENT`)** sobre el pool compartido multi-GB |
| **Formato de Vértices** | Empaquetado compacto de terreno | **Se conserva** (escrito directamente en `AreaBuffer` sin copias intermedias) |
| **Frustum & Occlusion Culling** | Recorrido secuencial en CPU | **GPU Indirect Culling (`cull.comp`) + Pirámide Hi-Z (`hiz_downsample.comp`)** |
| **Emisión de Draw Calls** | Bucle por regiones/secciones en CPU | **Dibujado indirecto en lote (`vkCmdDrawIndexedIndirect`)** |
| **Chunk Meshing** | Hilos trabajadores de CPU compitiendo por reloj | **Compute Mesher (`mesh.comp`) + Modo Híbrido (GPU + 1 Hilo CPU auxiliar)** |
| **WorldGen (Singleplayer)** | Ruido 3D secuencial en hilo de servidor | **Ruido 3D por lotes (`noise.comp`) con caché espacial *off-heap*** |
| **Interfaz de Usuario** | Menú unificado Sodium + Vulkan | **Se conserva** + nueva pestaña **Dosium Compute** y telemetría en **F3** |

---

## 2. Arquitectura Técnica

### A. Extensión de Memoria UMA Zero-Copy y Colas (`com.dosium.core`)
* **`UmaBufferBridge` + `MemoryTypes` (`UmaUnifiedMemory`):** Detecta automáticamente APUs e iGPUs con memoria unificada (UMA) y asigna los `AreaBuffer` e `IndirectBuffer` directamente sobre el *heap* compartido multi-GB (`HOST_VISIBLE | HOST_COHERENT`), escribiendo mediante `MemoryUtil.memCopy` (**Zero-Copy**) sin pasar por `StagingBuffer` ni saturar la cola de transferencia o el *carveout* BIOS.
* **`ComputeQueueManager`:** Compila en tiempo de ejecución los *Compute Shaders* GLSL (`#version 450`) a SPIR-V mediante `Shaderc` y gestiona consultas de tiempo por hardware (`VkQueryPool` con `VK_QUERY_TYPE_TIMESTAMP`).
* **`ApuBudgetController` (APU Guardrail):** Monitorea el tiempo activo de renderizado y cómputo frente al presupuesto objetivo del fotograma (según el límite de FPS configurado o el perfil seleccionado). Cuando la carga supera el **80%** del tiempo objetivo, regula dinámicamente la cuota de reconstrucción y subida de secciones por frame para mantener un *frame pacing* estable entre CPU y GPU.

### B. GPU Indirect Culling + Pirámide Hi-Z (`com.dosium.culling`)
* **`IndirectDrawManager`:** Empaqueta los *Bounding Boxes* (AABB) de las secciones activas de $16 \times 16 \times 16$ en un buffer *off-heap* para el SSBO de secciones y sincroniza el dibujado indirecto de Vulkan.
* **`HiZPyramidBuilder`:** Construye una pirámide de profundidad jerárquica conservadora (mip 0 y reducción $2 \times 2$ en mip 1 equivalente a `hiz_downsample.comp`) para descartar cuevas y secciones lejanas ocultas detrás de terreno sólido cercano.

### C. Compute Chunk Meshing (`com.dosium.meshing`)
* **`GpuSectionPacker`:** Empaqueta la vecindad de $18 \times 18 \times 18$ bloques ($16^3$ más borde vecino de 1 bloque, ~11.4 KB en memoria *off-heap*) codificando opacidad e índices de estado.
* **`ComputeMeshDispatcher`:** Coordina los modos `GPU Compute`, `Híbrido (GPU+CPU)` y `CPU Legacy`, ajustando automáticamente el número y la prioridad de los hilos constructores para que procesadores de 2 núcleos nunca sufran *stuttering*.

### D. Aceleración de WorldGen y Partículas (`com.dosium.worldgen`)
* **`GpuNoiseBridge`:** Evalúa y almacena en caché muestras de densidad de ruido 3D por lotes (`noise.comp`) para reducir la carga del servidor integrado en mundos de un jugador.

---

## 3. Estructura del Proyecto

```text
src/main/
├── java/
│   ├── me/jellysquid/mods/sodium/    # Estructuras de datos, UI base y mixins (Base: Sodium - LGPL-3.0)
│   ├── net/vulkanmod/                # Backend Vulkan, AreaBuffer, UploadManager y MemoryTypes (Base: VulkanMod - LGPL-3.0)
│   └── com/dosium/                   # Capa GPU-Driven y UMA Zero-Copy de Dosium (Nuevo - LGPL-3.0)
│       ├── core/
│       │   ├── DosiumConfig.java         # Configuración persistente (config/dosium-options.json)
│       │   ├── ComputeQueueManager.java  # Gestión de cola Compute, SPIR-V y VkQueryPool
│       │   ├── UmaBufferBridge.java      # Puente Zero-Copy hacia AreaBuffer de VulkanMod
│       │   └── ApuBudgetController.java  # Regulador térmico y de frame-budget para APUs
│       ├── culling/
│       │   ├── IndirectDrawManager.java  # Gestión de SSBO de secciones y dibujado indirecto
│       │   └── HiZPyramidBuilder.java    # Pirámide de profundidad jerárquica (Hi-Z)
│       ├── meshing/
│       │   ├── GpuSectionPacker.java     # Empaquetado off-heap 18x18x18 de secciones
│       │   └── ComputeMeshDispatcher.java# Despachador de meshing híbrido/compute
│       ├── worldgen/
│       │   └── GpuNoiseBridge.java       # Evaluación en lote de ruido 3D para Singleplayer
│       └── gui/
│           └── DosiumOptionsPage.java    # Pestaña "Dosium Compute" en el menú de opciones
└── resources/
    └── assets/vulkanmod/shaders/dosium/  # Compute Shaders GLSL (#version 450)
        ├── cull.comp
        ├── hiz_downsample.comp
        ├── mesh.comp
        ├── noise.comp
        └── particle_sim.comp
```

---

## 4. Compilación desde el Código Fuente

### Requisitos
* **JDK 17** o superior
* **Gradle 8.8+** (incluido mediante Gradle Wrapper)

### Generar el JAR unificado
```bash
./gradlew build --no-daemon
```
El artefacto final autoincluido se genera en `build/libs/`.

---

## 5. Licencias, Créditos y Cumplimiento Legal (LGPL-3.0)

**Dosium** se distribuye bajo la licencia **GNU Lesser General Public License v3.0 (`LGPL-3.0-only`)**.  
Consulta los archivos [`LICENSE.txt`](LICENSE.txt), [`NOTICE.md`](NOTICE.md) y [`vulkan-embedded/LICENSE_VulkanMod_1.20.4`](vulkan-embedded/LICENSE_VulkanMod_1.20.4) para el texto completo de la licencia y el registro detallado de modificaciones.

### Mods y Proyectos Base (Agradecimientos y Atribución)

1. **[Sodium](https://github.com/CaffeineMC/sodium-fabric)** — *Copyright (C) 2020–2024 JellySquid y colaboradores de CaffeineMC*
   * **Licencia:** GNU LGPL-3.0-only.
   * Proporciona la arquitectura de opciones (`SodiumOptionsGUI`), estructuras compactas de datos, utilidades de memoria nativa y mixins base de optimización.
2. **[VulkanMod](https://github.com/xCollateral/VulkanMod)** — *Copyright (C) 2021–2024 xCollateral y colaboradores*
   * **Licencia:** GNU LGPL-3.0-only.
   * Proporciona el backend de renderizado Vulkan (`net.vulkanmod.*`), gestión de colas, `SwapChain`, `AreaBuffer`, `IndirectBuffer`, `SectionGraph` y shaders base de terreno.
3. **[Sodium-Vulkan](https://github.com/Skypixel-ECAS/Sodium-Vulkan)** — *por Skypixel-ECAS*
   * **Licencia:** GNU LGPL-3.0-only.
   * Base inicial de compatibilidad de mixins entre Sodium y VulkanMod.
4. **Bibliotecas Nativas y Recursos Empaquetados:**
   * **[LWJGL 3](https://www.lwjgl.org/)** (`lwjgl-vulkan`, `lwjgl-vma`, `lwjgl-shaderc`) — Licencia **BSD-3-Clause**.
   * **[Vulkan Memory Allocator (VMA)](https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator)** por AMD — Licencia **MIT**.
   * **[Shaderc](https://github.com/google/shaderc)** por Google — Licencia **Apache-2.0**.
   * **Pack "Nicer Fast Leaves"** por **[Vanilla Tweaks](https://vanillatweaks.net/)** — Licenciado bajo **GNU LGPL-3.0-only**.
