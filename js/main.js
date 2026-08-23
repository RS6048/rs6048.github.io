// data is loaded from data.js (via script tag)

// Status tag definitions (special tags rendered with custom colors/icons)
const STATUS_TAGS = ['Ongoing', 'Halt', 'Finished'];
const STATUS_CONFIG = {
    'Ongoing': { icon: 'fa-circle-notch', cls: 'status-ongoing' },
    'Halt': { icon: 'fa-pause', cls: 'status-halt' },
    'Finished': { icon: 'fa-check', cls: 'status-finished' }
};

// Helper: render tags with Font Awesome (status tags get special styling)
function renderTags(tags) {
    return tags.map(tag => {
        if (STATUS_TAGS.includes(tag)) {
            const cfg = STATUS_CONFIG[tag];
            return `<span class="status-tag ${cfg.cls}"><i class="fas ${cfg.icon}"></i> ${tag}</span>`;
        }
        return `<span><i class="fas fa-tag"></i> ${tag}</span>`;
    }).join('');
}

// Homepage (root): render recent projects (max 4) with full path to projects/
function renderRecentProjects() {
    const container = document.getElementById('recentProjectList');
    if (!container) return;
    // projectsData is global from data.js
    const recent = projectsData.slice(0, 4);
    container.innerHTML = recent.map((proj, index) => {
        return `<div class="project-item" onclick="location.href='projects/${proj.folder}'">
            <h3>${proj.title}</h3>
            <p>${proj.description}</p>
            <div class="tags">${renderTags(proj.tags)}</div>
        </div>`;
    }).join('');
}

// Projects page (/projects/): render all projects with relative path (projectX/)
function renderAllProjects(filter = '') {
    const container = document.getElementById('projectGrid');
    if (!container) return;
    const query = filter.toLowerCase().trim();
    let filtered = projectsData;
    if (query) {
        filtered = projectsData.filter(p =>
            p.title.toLowerCase().includes(query) ||
            p.tags.some(t => t.toLowerCase().includes(query))
        );
    }
    container.innerHTML = filtered.map((proj) => {
        const idx = projectsData.findIndex(p => p.id === proj.id);
        return `<div class="project-card" onclick="location.href='${proj.folder}'">
            <h3>${proj.title}</h3>
            <p>${proj.description}</p>
            <div class="tags">${renderTags(proj.tags)}</div>
        </div>`;
    }).join('');
}

// Initialize based on current page
document.addEventListener('DOMContentLoaded', function() {
    if (document.getElementById('recentProjectList')) {
        renderRecentProjects();
    }
    if (document.getElementById('projectGrid')) {
        renderAllProjects('');
        const searchInput = document.getElementById('projectSearch');
        if (searchInput) {
            searchInput.addEventListener('input', function(e) {
                renderAllProjects(e.target.value);
            });
        }
    }
});