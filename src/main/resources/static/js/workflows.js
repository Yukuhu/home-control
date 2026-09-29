const editor = document.querySelector('.workflow-editor');
if (editor) {
    const form = editor.querySelector('#workflow-form');
    const limits = { calls: 8, headers: 16, variables: 64, entryVariables: 64 };

    const rowsOf = container => Array.from(container.children).filter(child => child.matches('[data-row]'));

    /** "calls[1].headers[0]": the rows that contain this element, outermost first. */
    function pathOf(element) {
        const parts = [];
        for (let row = element.closest('[data-row]'); row; row = row.parentElement.closest('[data-row]')) {
            parts.unshift(`${row.dataset.row}[${rowsOf(row.parentElement).indexOf(row)}]`);
        }
        return parts.join('.');
    }

    const idOf = name => `workflow-${name.replaceAll('[', '-').replaceAll('].', '-')}`;

    function reindex() {
        const targets = new Map();
        editor.querySelectorAll('[data-row] [data-field]').forEach(input => {
            const previous = input.id;
            const name = `${pathOf(input)}.${input.dataset.field}`;
            input.name = name;
            input.id = idOf(name);
            input.closest('[data-row]').querySelectorAll('label').forEach(label => {
                if (previous && label.htmlFor === previous) label.htmlFor = input.id;
                if (!previous && !label.htmlFor && label.parentElement === input.parentElement) label.htmlFor = input.id;
            });
            // A row added just now owns no existing error links.
            if (previous && !input.closest('[data-new-row]')) targets.set(`#${previous}`, `#${input.id}`);
        });
        editor.querySelectorAll('[data-row] [data-marker]').forEach(input => {
            input.name = `_${pathOf(input)}.${input.dataset.marker}`;
        });
        editor.querySelectorAll('.error a[href^="#workflow-"]').forEach(link => {
            const target = targets.get(link.getAttribute('href'));
            if (target) link.setAttribute('href', target);
        });
        editor.querySelectorAll('[data-new-row]').forEach(row => delete row.dataset.newRow);
        editor.querySelectorAll('[data-add-row]').forEach(button => {
            button.disabled = rowsOf(button.previousElementSibling).length >= limits[button.dataset.addRow];
        });
        rowsOf(editor.querySelector('[data-rows="calls"]')).forEach((card, index) => {
            card.querySelector('h3').textContent = `Call ${index + 1}`;
        });
        callNames();
    }

    /** The entry source follows its call through renames and moves; each option remembers its name field. */
    const sources = new WeakMap();

    function callNames() {
        const select = form.elements.entryCall;
        if (!select) return;
        const inputs = Array.from(editor.querySelectorAll('[data-rows="calls"] [data-call-name]'))
            .filter(input => input.value.trim());
        const chosen = select.selectedOptions[0];
        const chosenInput = chosen ? sources.get(chosen) : undefined;
        const chosenName = select.value;
        const options = inputs.map(input => {
            const option = new Option(input.value.trim(), input.value.trim());
            sources.set(option, input);
            return option;
        });
        select.replaceChildren(...options);
        const match = options.find(option => sources.get(option) === chosenInput)
            ?? options.find(option => option.value === chosenName)
            ?? options[0];
        if (match) match.selected = true;
    }

    /** A call that was never saved has no saved URL or headers to keep. */
    function lockNewCalls() {
        editor.querySelectorAll('[data-row="calls"]').forEach(card => {
            if (card.querySelector('[data-field="savedName"]').value) return;
            ['urlMode', 'headersMode'].forEach(field => {
                const select = card.querySelector(`[data-field="${field}"]`);
                select.querySelector('option[value="KEEP"]').disabled = true;
                select.value = 'REPLACE';
            });
        });
        if (form.elements.expectedRevision.value === '0') {
            const keep = form.elements.templateMode.querySelector('option[value="KEEP"]');
            keep.disabled = true;
            form.elements.templateMode.value = 'REPLACE';
        }
    }

    function visibility() {
        const mode = form.elements.mode.value;
        editor.querySelectorAll('select[data-field="scope"]').forEach(select => {
            const entry = select.querySelector('option[value="ENTRY"]');
            if (mode !== 'GENERATED') {
                select.value = 'SHARED';
                entry?.remove();
            } else if (!entry) {
                select.add(new Option('Once per entry', 'ENTRY'));
            }
        });
        editor.querySelectorAll('[data-mode]').forEach(section => { section.hidden = section.dataset.mode !== mode; });
        editor.querySelectorAll('[data-replacement]').forEach(section => {
            const card = section.closest('[data-row]');
            const control = card ? card.querySelector(`[data-field="${section.dataset.replacement}"]`)
                : form.elements[section.dataset.replacement];
            section.hidden = control.value === 'KEEP';
        });
        editor.querySelectorAll('[data-choice]').forEach(section => {
            const [name, value] = section.dataset.choice.split(':');
            section.hidden = form.elements[name].value !== value;
        });
    }

    const firstInput = row => row.querySelector('input:not([type="hidden"])');

    editor.addEventListener('click', event => {
        const add = event.target.closest('[data-add-row]');
        const remove = event.target.closest('[data-remove-row]');
        const move = event.target.closest('[data-move-row]');
        if (add) {
            const family = add.dataset.addRow;
            const rows = add.previousElementSibling;
            if (rowsOf(rows).length >= limits[family]) return;
            const template = editor.querySelector(`#workflow-${family}-template`);
            const fragment = template.content.cloneNode(true);
            const row = fragment.querySelector('[data-row]');
            row.dataset.newRow = 'true';
            rows.append(fragment);
            reindex();
            lockNewCalls();
            visibility();
            firstInput(row).focus();
        } else if (remove) {
            const row = remove.closest(`[data-row="${remove.dataset.removeRow}"]`);
            const container = row.parentElement;
            const next = row.nextElementSibling || row.previousElementSibling;
            row.querySelectorAll('[data-field]').forEach(input => {
                editor.querySelectorAll(`a[href="#${input.id}"]`).forEach(link => link.setAttribute('href', '#workflow-form'));
            });
            row.remove();
            reindex();
            visibility();
            (next ? firstInput(next) : container.nextElementSibling).focus();
        } else if (move) {
            const card = move.closest('[data-row="calls"]');
            const up = move.dataset.moveRow === 'up';
            const sibling = up ? card.previousElementSibling : card.nextElementSibling;
            if (!sibling) return;
            if (up) sibling.before(card); else sibling.after(card);
            reindex();
            move.focus();
        }
    });
    form.addEventListener('change', visibility);
    form.addEventListener('input', event => {
        if (event.target.matches('[data-call-name]')) callNames();
    });
    reindex();
    lockNewCalls();
    visibility();
}
