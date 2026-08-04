// data is loaded from data.js (via script tag)

// Helper: render tags with Font Awesome
function renderTags(tags) {
    return tags.map(tag =>
        `<span><i class="fas fa-tag"></i> ${tag}</span>`
    ).join('');
}

// Homepage (root): render recent projects (max 4) with full path to projects/
function renderRecentProjects() {
    const container = document.getElementById('recentProjectList');
    if (!container) return;
    // projectsData is global from data.js
    const recent = projectsData.slice(0, 4);
    const projectFolders = ['projectA', 'projectB', 'projectC', 'projectD', 'projectE'];
    container.innerHTML = recent.map((proj, index) => {
        const folder = projectFolders[index] || `project${String.fromCharCode(65 + index)}`;
        return `<div class="project-item" onclick="location.href='projects/${folder}/index.html'">
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
    const projectFolders = ['projectA', 'projectB', 'projectC', 'projectD', 'projectE'];
    container.innerHTML = filtered.map((proj) => {
        const idx = projectsData.findIndex(p => p.id === proj.id);
        const folder = projectFolders[idx] || `project${String.fromCharCode(65 + idx)}`;
        return `<div class="project-card" onclick="location.href='${folder}/index.html'">
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
