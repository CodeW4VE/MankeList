<div align="center">

# MankeList

<img src="src/main/resources/assets/mankelist/icon.png" alt="MankeList icon" width="220">

**Lista de materiales compartida para proyectos comunitarios: HUD en vivo, checks por chat, claims, control de stock y un board de Discord que se actualiza solo. Un solo jar.**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%20%2F%2026.3-brightgreen?logo=minecraft)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue)](https://fabricmc.net/)
[![Requires Fabric API](https://img.shields.io/badge/Requiere-Fabric%20API-blue)](https://modrinth.com/mod/fabric-api)
[![Environment](https://img.shields.io/badge/Entorno-Server%20%2B%20cliente%20opcional-orange)](#instalación)
[![License](https://img.shields.io/badge/Licencia-MIT-yellow.svg)](LICENSE)

[English](README.md) | Español

</div>


## Compatibilidad y actualización

Usá el jar correspondiente a **1.21 / 1.21.1** o **26.3**, junto con Fabric API para esa versión. Minecraft 26.3 necesita Java 25 y Fabric Loader 0.19.5 o superior; 1.21 necesita Java 21.

Conservá `config/mankelist/` y los archivos `mankelist-*.json` al actualizar. Se mantienen las listas, los materiales marcados, las asignaciones, los seguidores y las zonas de almacenamiento. Las listas activas se restauran al iniciar el servidor dedicado.

---

## ¿Qué es?

Tu comunidad está construyendo algo grande. Todos necesitan saber **qué
materiales faltan, quién está farmeando qué y cuánto llevan**, sin perseguir a
un admin ni abrir una hoja de cálculo.

**MankeList** mantiene UNA lista de materiales compartida en el server:

- 📥 **Importa directo de un `.litematic`**: `/ml import` parsea la schematic
  en el server y arma la lista (sin herramientas externas).
- ✅ **Checks colaborativos**: cualquiera puede marcar con `/ml check` un
  material terminado; el server entero ve el anuncio.
- 🐵 **El toque de Manke (opt-in)**: quien haga `/ml follow` recibe un
  privado *"Ooh ooh! Manke sees you carry enough Hopper (48 / 18). Check it
  off? [✓ Yes]"* cuando su inventario cubre un material pendiente. Funciona
  también para jugadores **sin el mod** (es chat clickeable normal).
- ⛏ **Claims**: `/ml claim obsidian` avisa que estás con eso. Al checkearse,
  el nombre queda como *quién lo consiguió*.
- 📦 **Stocking areas**: marca el cuarto de cofres de entrega con
  `/ml stockarea add` y el server cuenta lo ya guardado (shulkers anidados
  incluidos). El progreso pasa a ser *fino*: 190k de obsidiana en cofres
  cuentan, aunque nadie haya checkeado el material todavía.
- 🖥️ **HUD opcional en el cliente**: overlay estilo Litematica con conteo en
  vivo *llevas + guardado / necesita* contra tu propio inventario, orden
  personalizado y ciclado multi-lista. Quien no instale el mod cliente solo se
  pierde el overlay.
- 📊 **Board de Discord integrado**: pega la URL de un webhook en la config y
  el mod postea la lista completa en un canal y **edita siempre los mismos
  mensajes**: un board en vivo con barras de progreso, 📦 stock y ⛏ claims.
  Sin bot, sin procesos extra.

<div align="center">
<img src="docs/img/demo.gif" alt="Check de un material y claim de otro, en vivo en el HUD" width="820">
</div>

## Instalación

**Server** (obligatorio): suelta `mankelist-x.y.z.jar` + [Fabric API](https://modrinth.com/mod/fabric-api)
en `mods/` del server. Listo: todo lo de arriba funciona con cliente vanilla.

**Cliente** (opcional, para el HUD): instala el mismo jar en el cliente. El
server solo le manda la lista a clientes con el mod (payloads custom estilo
Servux); a los vanilla no les llega nada.

## Arranque rápido

```
/ml import mi_proyecto.litematic     ← arma y activa la lista desde la schematic
/ml follow                           ← te apuntas a los avisos de Manke
/ml stockarea add 100 60 100 120 70 120   ← el cuarto de cofres de entrega
```

Y a farmear: se marca con el **[✓ Yes]** de Manke o a mano con
`/ml check <material>`.

<img src="docs/img/manke_prompt.png" alt="El aviso de inventario de Manke" width="700">

## Comandos

Para todos: `/ml status`, `/ml check`, `/ml uncheck`, `/ml claim`,
`/ml have <cantidad> <material>` (progreso parcial: "llevo 5.000 azaleas", te
claimea el material y suma a las barras), `/ml unclaim`, `/ml follow`,
`/ml unfollow`, `/ml stock` (reporte compacto de lo ya guardado), `/ml lists`.

Para OPs (nivel 2): `/ml import`, `/ml load` (relee el archivo, **resetea
checks**), `/ml switch` (reactiva una lista guardada **conservando el
progreso**), `/ml unload`, `/ml reset [lista]`, `/ml stockarea add/list/clear`.

Puede haber varias listas activas a la vez; si un material está en más de
una, se desambigua como `lista/material` (ej. `/ml check xpfarm/hopper`); el
autocompletado y los botones de Manke lo hacen solos. La tabla completa está
en el [README en inglés](README.md#commands).

## El HUD (mod cliente)

<img src="docs/img/hud.png" alt="El HUD con conteo de inventario en vivo" width="820">

<div align="center">
<img src="docs/img/config.gif" alt="Ajustando el HUD in-game con Shift+J" width="700">
<img src="docs/img/discord.gif" alt="Un check in-game editando el board de Discord en vivo" width="820">
</div>

`J` muestra/oculta, `Shift+J` abre los ajustes in-game (también desde
Mod Menu o una tecla asignable) y `K` cicla la lista enfocada. Todas las teclas se pueden reasignar en
**Opciones → Controles**, categoría *MankeList*, por si `J`/`K` chocan con
otro mod. Las filas pendientes muestran **llevas +
guardado / necesita** con colores rojo/amarillo/verde; las hechas se
contraen a un ✓ y los claims salen como `@Nombre`. Orden por cantidad o
totalmente personalizado (agarrar y soltar, con buscador).

## Board de Discord

1. En tu canal: **Editar canal → Integraciones → Webhooks → Nuevo webhook**,
   copia la URL.
2. Pégala en `config/mankelist-server.json` → `"discordWebhookUrl"`.
3. Reinicia. El mod postea el board y desde ahí **lo edita en el sitio** cada
   vez que la lista cambia (máximo una vez cada 10 s).

La referencia completa de la config del server está en el
[README en inglés](README.md#server-config-configmankelist-serverjson).

## Licencia

[MIT](LICENSE). El layout del HUD está inspirado en la material list de
Litematica; escrito de cero, sin código copiado.
