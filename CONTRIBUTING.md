# Contribuir a Dosium

¡Gracias por tu interés en contribuir a **Dosium** ([https://github.com/spyflow/dosium](https://github.com/spyflow/dosium))!

## Reportar Problemas (Issues)

Al abrir un issue en [https://github.com/spyflow/dosium/issues](https://github.com/spyflow/dosium/issues), incluye la siguiente información:

- La versión exacta de **Dosium** y de **Fabric Loader** en Minecraft `1.20.4`.
- Modelo de CPU/APU o GPU dedicada, versión del driver de Vulkan y sistema operativo.
- Captura de la pantalla de depuración **F3** mostrando la sección `Dosium (Sodium + Vulkan Compute)` (`Device Memory`, `GPU Cull`, `GPU Mesh`, `APU Budget` y `Memory Path`).
- Si ocurre un cierre inesperado (*crash*), adjunta el archivo `latest.log` o el reporte de crash completo.
- **Importante:** No reportes problemas de **Dosium** en los repositorios oficiales de Sodium (`CaffeineMC/sodium-fabric`) ni de VulkanMod (`xCollateral/VulkanMod`), ya que Dosium modifica profundamente el backend de memoria y renderizado.

## Acuerdo de Licencia de Contribuidor y Cumplimiento LGPL-3.0

Al enviar cambios a este repositorio, aceptas que:

1. Tus contribuciones se licenciarán bajo la **[GNU Lesser General Public License v3.0 (LGPL-3.0-only)](https://www.gnu.org/licenses/lgpl-3.0.html)**, en total conformidad con las licencias de los proyectos base:
   - **[Sodium](https://github.com/CaffeineMC/sodium-fabric)** por *JellySquid y CaffeineMC* (`LGPL-3.0-only`).
   - **[VulkanMod](https://github.com/xCollateral/VulkanMod)** por *xCollateral y colaboradores* (`LGPL-3.0-only`).
   - **[Sodium-Vulkan](https://github.com/Skypixel-ECAS/Sodium-Vulkan)** por *Skypixel-ECAS* (`LGPL-3.0-only`).
2. Deben conservarse todos los encabezados de copyright y avisos de licencia originales en los archivos derivados de `me.jellysquid.mods.sodium.*` y `net.vulkanmod.*`, y cualquier modificación sustancial debe quedar reflejada en [`NOTICE.md`](NOTICE.md).

## Estilo de Código y Directrices Técnicas

- **Rendimiento en APUs / UMA:** Cualquier cambio en los *Compute Shaders* (`assets/vulkanmod/shaders/dosium/`) o en el gestor de memoria (`UmaBufferBridge` / `MemoryTypes`) debe preservar el camino **Zero-Copy** en memorias unificadas y respetar el límite del 80% de presupuesto por fotograma de `ApuBudgetController`.
- **Formato:** Usa 4 espacios para la indentación, evita líneas mayores a 120 caracteres y verifica que `./gradlew build --no-daemon` compile limpiamente antes de abrir un Pull Request.
