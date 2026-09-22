const editor = document.querySelector('.workflow-editor');
if (editor) {
    const form = editor.querySelector('#workflow-form');
    const limits = { variables: 32, headers: 16 };
    function reindex(family) {
        const rows = editor.querySelector(`[data-rows="${family}"]`);
        const links = Array.from(editor.querySelectorAll('a[href]'), link => [link, link.getAttribute('href')]);
        const targets = new Map();
        rows.querySelectorAll('[data-row]').forEach((row, index) => {
            row.querySelectorAll('[data-field]').forEach(input => {
                const previous = input.id;
                const id = `workflow-${family}-${index}-${input.dataset.field}`;
                input.name = `${family}[${index}].${input.dataset.field}`;
                input.id = id;
                row.querySelectorAll('label').forEach(label => {
                    if (label.htmlFor === previous) label.htmlFor = id;
                });
                // A cloned template starts with row-zero IDs but owns no existing error links.
                if (!row.dataset.newRow) targets.set(`#${previous}`, `#${id}`);
            });
            row.querySelectorAll('[data-marker]').forEach(input => {
                input.name = `_${family}[${index}].${input.dataset.marker}`;
            });
            delete row.dataset.newRow;
        });
        links.forEach(([link, previous]) => {
            if (targets.has(previous)) link.setAttribute('href', targets.get(previous));
        });
        editor.querySelector(`[data-add-row="${family}"]`).disabled = rows.children.length >= limits[family];
    }
    function visibility() {
        editor.querySelectorAll('[data-mode]').forEach(section => {
            section.hidden = section.dataset.mode !== form.elements.mode.value;
        });
        editor.querySelectorAll('[data-replacement]').forEach(section => {
            section.hidden = form.elements[section.dataset.replacement].value === 'KEEP';
        });
        editor.querySelectorAll('[data-optional]').forEach(section => {
            section.hidden = !form.elements[section.dataset.optional].checked;
        });
    }
    editor.addEventListener('click', event => {
        const add = event.target.closest('[data-add-row]');
        const remove = event.target.closest('[data-remove-row]');
        if (add) {
            const family = add.dataset.addRow;
            const rows = editor.querySelector(`[data-rows="${family}"]`);
            if (rows.children.length >= limits[family]) return;
            const template = editor.querySelector(`#workflow-${family}-template`);
            const fragment = template.content.cloneNode(true);
            fragment.querySelector('[data-row]').dataset.newRow = 'true';
            rows.append(fragment);
            reindex(family);
            rows.lastElementChild.querySelector('input').focus();
        } else if (remove) {
            const family = remove.dataset.removeRow;
            const row = remove.closest('[data-row]');
            const next = row.nextElementSibling || row.previousElementSibling;
            row.querySelectorAll('[data-field]').forEach(input => {
                editor.querySelectorAll(`a[href="#${input.id}"]`).forEach(link => link.setAttribute('href', '#workflow-form'));
            });
            row.remove();
            reindex(family);
            (next?.querySelector('input') || editor.querySelector(`[data-add-row="${family}"]`)).focus();
        }
    });
    form.addEventListener('change', visibility);
    // A new workflow cannot retain credentials that have not been saved yet.
    if (form.elements.expectedRevision.value === '0') {
        ['urlMode', 'templateMode', 'headersMode'].forEach(name => {
            const keep = form.elements[name].querySelector('option[value="KEEP"]');
            keep.disabled = true;
            form.elements[name].value = 'REPLACE';
        });
    }
    reindex('variables'); reindex('headers'); visibility();
}
