---
name: Fetchy
description: Reference-led Android media saving and file organization
colors:
  primary: "#0a6bff"
  background: "#fafafb"
  surface: "#ffffff"
  field: "#f0f1f4"
  ink: "#181b20"
  action: "#191e23"
  dark-background: "#17191e"
  dark-surface: "#202229"
  dark-field: "#23262d"
rounded:
  thumbnail: "12px"
  field: "14px"
  card: "16px"
  sheet: "26px"
spacing:
  page: "20px"
  grid: "6px"
  folders: "12px"
typography:
  title:
    fontFamily: "Android sans-serif"
    fontSize: "26px"
    fontWeight: 800
    letterSpacing: "-0.6px"
  body:
    fontFamily: "Android sans-serif"
    fontSize: "14px"
    fontWeight: 400
  label:
    fontFamily: "Android sans-serif"
    fontSize: "12px"
    fontWeight: 600
components:
  button-primary:
    backgroundColor: "{colors.action}"
    textColor: "{colors.surface}"
    height: "50px"
    rounded: "999px"
  field:
    backgroundColor: "{colors.field}"
    rounded: "{rounded.field}"
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.card}"
---

## Overview

**Creative North Star: "Save simply, browse visually."**

The five user-provided Fetchy references govern the app's own surfaces. The interface is quiet and neutral; real media and colored platform folders carry the visual emphasis. CSS-compatible measurements above mirror Flutter logical pixels, independent of screenshot device density.

**Key Characteristics:**

- Neutral surfaces with blue selected states.
- Black pill actions for saving, applying and creating.
- Two-column media and folder browsing.
- Rounded sheets, a subtle grip and grouped action rows.

## Colors

Primary blue identifies active navigation, selections and switches. The dark scheme keeps the same structure with lighter action buttons. Folder color is persistent user metadata; a platform mark retains its own brand colors.

## Typography

Android sans-serif and native Korean fallback follow system scaling. Main titles use the title scale, contextual folder/settings headers use 20 logical pixels, and metadata is secondary. Long filenames truncate in tiles and wrap in details.

## Layout

**The Current Folder Rule.** The floating plus creates a folder under the location currently being browsed; the parent remains editable.

Page content has the shared inset. Media grids have narrow gaps; folder cards have wider gaps. Phone content is capped at 760 logical pixels. Media grid children mount lazily as they enter the viewport. Analysis and share saving actions remain outside the scrollable selection list. Android Back moves up the folder tree or exits the current analysis/detail route.

## Elevation & Depth

Cards are flat. The white circular folder FAB has restrained Material elevation. System modal dimming separates sheets from the current screen.

## Shapes

Thumbnail, field, card and sheet corners use the token scale. Primary buttons are pills. The two overlapping blue/pink ovals are authored vector geometry, also used in the Android launcher mark.

## Components

- Home: URL field, platform shortcuts, recent links and native download activity.
- Files: filter chips, two-column real folders/media, duration and actual progress overlays.
- Details: large preview, three system actions and measured properties.
- Sheets: folder selection, create/parent/color, file/folder context actions and format variants.
- Settings: primary download/network cards, retry switch, then advanced settings and cookies/cache/licenses.

## Do's and Don'ts

- Use actual files, metadata and download state.
- Preserve the reference hierarchy and existing native contracts.
- Keep loading, missing-file and empty states readable.
- Do not fabricate sample counts, media or format availability.
- Do not imitate or replace Android system sharing and permission screens.
