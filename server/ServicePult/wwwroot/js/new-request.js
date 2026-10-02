// «Новая заявка»: списки объектов, оборудования и уровней SLA зависят от выбранного договора и объекта.
// Это только удобство: те же правила проверяет сервер при сохранении.
(function () {
    const contract = document.getElementById('ContractId');
    const site = document.getElementById('SiteId');
    const equipment = document.getElementById('EquipmentId');
    const sla = document.getElementById('SlaLevelId');
    const slaHint = document.getElementById('sla-hint');
    if (!contract) return;

    // показывает только подходящие варианты; если выбранный стал неподходящим — сбрасывает выбор
    function filter(select, fits) {
        for (const option of select.options) {
            if (!option.value) continue;
            const ok = fits(option);
            option.hidden = !ok;
            option.disabled = !ok;
        }
        const selected = select.options[select.selectedIndex];
        if (selected && selected.disabled) select.value = '';
    }

    function onContract() {
        const c = contract.options[contract.selectedIndex];
        const customer = c && c.value ? c.dataset.customer : null;
        const maxSla = c && c.value ? Number(c.dataset.maxSla) : null;
        filter(site, o => customer === null || o.dataset.customer === customer);
        filter(sla, o => maxSla === null || Number(o.dataset.level) >= maxSla);
        slaHint.textContent = maxSla === null
            ? 'Доступны уровни не выше разрешённого договором.'
            : 'Договор допускает уровень не выше SLA-' + maxSla + ' (SLA-1 — самый высокий).';
        onSite();
    }

    function onSite() {
        const siteId = site.value || null;
        filter(equipment, o => siteId !== null && o.dataset.site === siteId);
    }

    contract.addEventListener('change', onContract);
    site.addEventListener('change', onSite);
    onContract();
})();
