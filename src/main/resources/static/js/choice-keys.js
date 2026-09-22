// Arrow keys move and select within a tab list or radio group. Tab itself stays native.
export function bindChoiceKeys(group, selector, choose) {
    group?.addEventListener("keydown", (event) => {
        if (event.altKey || event.ctrlKey || event.metaKey) return;
        const choices = [...group.querySelectorAll(selector)];
        const index = choices.indexOf(event.target);
        if (index < 0) return;
        let next;
        if (event.key === "ArrowRight" || event.key === "ArrowDown") next = (index + 1) % choices.length;
        else if (event.key === "ArrowLeft" || event.key === "ArrowUp") next = (index + choices.length - 1) % choices.length;
        else if (event.key === "Home") next = 0;
        else if (event.key === "End") next = choices.length - 1;
        else return;
        event.preventDefault();
        choose(choices[next]);
        choices[next].focus();
    });
}
