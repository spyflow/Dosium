# Aviso de Derechos de Autor, Atribuciones y Licencias (NOTICE)

**Proyecto:** Dosium  
**Repositorio:** https://github.com/spyflow/dosium  
**Licencia del Proyecto Combinado:** GNU Lesser General Public License v3.0 (`LGPL-3.0-only`)

Este proyecto (**Dosium**) es una obra derivada y combinada conforme a los términos de la **GNU Lesser General Public License v3.0 (LGPL-3.0)**. En cumplimiento con la Sección 2 de la LGPL-3.0 y las Secciones 4 y 5 de la GPL-3.0, se documentan a continuación los proyectos originales sobre los cuales se construye Dosium, sus autores, sus licencias y el registro de modificaciones realizadas.

---

## 1. Proyectos Base (Licencia GNU LGPL-3.0-only)

### A. Sodium (por JellySquid y colaboradores de CaffeineMC)
* **Repositorio original:** https://github.com/CaffeineMC/sodium-fabric
* **Copyright:** Copyright (C) 2020-2024 JellySquid y colaboradores de CaffeineMC.
* **Licencia:** GNU Lesser General Public License v3.0 (`LGPL-3.0-only`) — ver archivo [`LICENSE.txt`](LICENSE.txt).
* **Componentes utilizados:** Estructuras de datos de cliente, compresión de vértices, sistema de configuración e interfaz gráfica (`SodiumOptionsGUI`), utilidades de memoria *off-heap* y mixins de optimización.

### B. VulkanMod (por xCollateral y colaboradores)
* **Repositorio original:** https://github.com/xCollateral/VulkanMod
* **Copyright:** Copyright (C) 2021-2024 xCollateral y colaboradores.
* **Licencia:** GNU Lesser General Public License v3.0 (`LGPL-3.0-only`) — ver archivo [`vulkan-embedded/LICENSE_VulkanMod_1.20.4`](vulkan-embedded/LICENSE_VulkanMod_1.20.4).
* **Componentes utilizados:** Backend de renderizado Vulkan (`net.vulkanmod.*`), gestión de `VkInstance`, `VkDevice`, `SwapChain`, `Pipeline`, `AreaBuffer`, `IndirectBuffer`, `SectionGraph`, `TaskDispatcher`, `BlockRenderer` y shaders GLSL base.

### C. Sodium-Vulkan (por Skypixel-ECAS)
* **Repositorio original:** https://github.com/Skypixel-ECAS/Sodium-Vulkan
* **Licencia:** GNU Lesser General Public License v3.0 (`LGPL-3.0-only`).
* **Componentes utilizados:** Estructura base de adaptación inicial entre Sodium y VulkanMod.

---

## 2. Registro de Modificaciones en Dosium (Conforme a LGPL-3.0 §2 / GPL-3.0 §5a)

El código original de **Sodium** y **VulkanMod** ha sido modificado y extendido en **Dosium** (2026) con los siguientes cambios principales:
1. **Eliminación de dependencias OpenGL:** Desacoplamiento completo de contextos y llamadas OpenGL en el ciclo de inicialización y depuración de Sodium, delegando el 100% del renderizado al backend de Vulkan.
2. **Arquitectura UMA Zero-Copy (`com.dosium.core.UmaBufferBridge`, `MemoryTypes`, `UploadManager`):** Detección automática de iGPUs/APUs con *carveout* reducido y escritura directa en memoria unificada (`HOST_VISIBLE | HOST_COHERENT`) sin *staging buffers* intermedios.
3. **Pipeline GPU-Driven & Compute (`com.dosium.*` y `assets/vulkanmod/shaders/dosium/*.comp`):**
   * Adición de *compute shaders* GLSL (`cull.comp`, `hiz_downsample.comp`, `mesh.comp`, `noise.comp`, `particle_sim.comp`).
   * Implementación de `ComputeQueueManager`, `ApuBudgetController`, `IndirectDrawManager`, `HiZPyramidBuilder`, `GpuSectionPacker`, `ComputeMeshDispatcher` y `GpuNoiseBridge`.
4. **Unificación de Interfaz y Telemetría:** Integración de las pestañas **Vulkan** y **Dosium Compute** dentro de `SodiumOptionsGUI` y telemetría de tiempos de GPU y memoria UMA en la pantalla de depuración **F3** (`MixinDebugHud`).

---

## 3. Bibliotecas y Recursos de Terceros Incluidos

1. **LWJGL 3 (`lwjgl-vulkan`, `lwjgl-vma`, `lwjgl-shaderc`)**
   * **Sitio web:** https://www.lwjgl.org/
   * **Licencia:** BSD-3-Clause License.
2. **Vulkan Memory Allocator (VMA)**
   * **Autor:** Advanced Micro Devices, Inc. (AMD) — https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator
   * **Licencia:** MIT License.
3. **Shaderc / glslang / SPIRV-Tools**
   * **Autor:** Google Inc. & The Khronos Group — https://github.com/google/shaderc
   * **Licencia:** Apache License 2.0.
4. **Nicer Fast Leaves (Recursos de texturas/modelos)**
   * **Autor:** [Vanilla Tweaks](https://vanillatweaks.net/)
   * **Licencia:** Licenciado para Sodium y sus derivados bajo GNU LGPLv3.
