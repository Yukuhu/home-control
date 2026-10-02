# Themes

Open **Setup → Appearance** to install, choose, export or remove themes. The header's **Theme** picker changes the
look without reloading the page. Your choice belongs to this browser; other open tabs in the same browser follow it.
Installing a theme makes it available to everyone using this Home Control installation, but leaves their choices
alone.

**Default and Cyberpunk are permanent.** Both can be selected and exported. Neither can be removed or replaced by
an import. Home Control releases update these bundled themes.

## Install a shared theme

1. Save the theme ZIP to your computer or phone.
2. In Appearance, choose the ZIP and select **Review theme**.
3. Review the name, author and version, then select **Install theme**.
4. Choose the installed theme with **Use theme** or the header picker.

If the same ID is already installed, the review offers **Update theme** and shows the version being replaced.
The update is applied only after confirmation. If another browser updates it while you are reviewing, review the
package again before replacing that newer version.

Packages include their images and fonts, so a theme does not need an external image or font service. Uploads are
limited to 10 MiB; the app also checks the expanded package size and its contents. A rejected upload leaves the
installed themes alone. The review's author and version come from the package, not from a verified author account.

## Make and share a theme

1. **Export** Default or Cyberpunk as a starting point.
2. Unzip it. In `theme.json`, give it a new `id`, name and author. Built-in IDs are reserved.
3. Edit the appearance values in `tokens.json`, and optionally the decorations in `theme.css`.
4. Keep font and image licence notices with the package. Package assets stay under `assets/`.
5. ZIP the contents with `theme.json` at the ZIP root, then review and install it in Appearance.
6. Export the installed theme to share the same package with someone else.

The [author guide](../dev/themes.md) describes the file format and styling contract. Home Control keeps control of
the page structure and device actions; theme packages contain appearance and assets.

## Remove or recover

Imported themes have a **Remove** action. A browser using a removed theme returns to Default when it refreshes
its theme list or navigates. The browser that removes it refreshes immediately.

If a theme makes the interface difficult to use, open **`/setup/appearance/recovery`** on your Home Control server.
That page, and its login screen if needed, always use Default. **Reset to Default** resets this browser's choice;
the page also lets you remove an imported theme.

When HTTPS offline support is available, the offline page retains both built-in themes and one completely cached
custom theme. If the selected custom theme is unavailable offline, the page uses Default. Device commands continue
to require the server and a connected device.
