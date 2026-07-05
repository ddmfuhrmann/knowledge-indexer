package io.github.ddmfuhrmann.kindexer.render;

import io.github.ddmfuhrmann.kindexer.manifest.Manifest;
import io.github.ddmfuhrmann.kindexer.model.CallGraph;
import io.github.ddmfuhrmann.kindexer.model.Entities.EntityModel;
import io.github.ddmfuhrmann.kindexer.model.Entities.FieldModel;
import io.github.ddmfuhrmann.kindexer.model.Entities.RelationModel;
import io.github.ddmfuhrmann.kindexer.model.EntryPoints.EntryPoint;
import io.github.ddmfuhrmann.kindexer.model.Flows.ExceptionStatus;
import io.github.ddmfuhrmann.kindexer.model.Flows.GuardCheck;
import io.github.ddmfuhrmann.kindexer.model.Flows.InputConstraint;
import io.github.ddmfuhrmann.kindexer.model.Flows.ThrowSite;
import io.github.ddmfuhrmann.kindexer.model.Migrations.MigrationFk;
import io.github.ddmfuhrmann.kindexer.model.StateMachines.StateMachine;
import io.github.ddmfuhrmann.kindexer.model.TestScenarios.Scenario;
import io.github.ddmfuhrmann.kindexer.model.TestScenarios.TestUnit;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the manifest — and only the manifest — into a self-contained HTML page. Diagrams are
 * Mermaid (loaded from CDN; the file is opened directly in a browser, not sandboxed). Every item
 * that came from the LLM carries an "interpreted" badge with its evidence in a tooltip, so a reader
 * always sees what is extracted fact versus interpretation.
 */
public final class HtmlRenderer {

    @SuppressWarnings("unchecked")
    public String render(Manifest m) {
        List<EntityModel> entities = (List<EntityModel>) m.artifacts().get("entities").data();
        List<EntryPoint> entryPoints = (List<EntryPoint>) m.artifacts().get("entryPoints").data();
        CallGraph callGraph = (CallGraph) m.artifacts().get("callGraph").data();
        List<StateMachine> stateMachines = (List<StateMachine>) m.artifacts().get("stateMachines").data();
        List<TestUnit> tests = (List<TestUnit>) m.artifacts().get("testScenarios").data();
        List<MigrationFk> migrations = (List<MigrationFk>) m.artifacts().get("migrations").data();
        List<ThrowSite> throwSites = (List<ThrowSite>) m.artifacts().get("throwSites").data();
        List<ExceptionStatus> exceptionStatuses = (List<ExceptionStatus>) m.artifacts().get("exceptionStatuses").data();
        List<InputConstraint> inputConstraints = (List<InputConstraint>) m.artifacts().get("inputConstraints").data();
        List<GuardCheck> guardChecks = (List<GuardCheck>) m.artifacts().get("guardChecks").data();
        var flows = new FlowContext(entryPoints, callGraph, throwSites, exceptionStatuses, inputConstraints, guardChecks);

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html>\n<html lang=\"en\"><head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Knowledge Index — ").append(esc(m.project().name())).append("</title>\n")
                .append(css())
                .append("<script>(function(){var t=localStorage.getItem('kindex-theme')"
                        + "||(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');"
                        + "document.documentElement.setAttribute('data-theme',t);})();</script>\n")
                .append("<script src=\"https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js\"></script>\n")
                .append("""
                        <script>
                        // Theme: resolve saved/system preference, drive both the CSS palette and Mermaid.
                        function kindexTheme(){ return document.documentElement.getAttribute('data-theme'); }
                        function mermaidTheme(t){ return t==='dark'?'dark':'neutral'; }
                        mermaid.initialize({startOnLoad:false, theme: mermaidTheme(kindexTheme())});
                        // Cache each diagram's source so it survives re-rendering (theme change).
                        function prep(el){
                          if(el.dataset.src===undefined) el.dataset.src = el.textContent;
                          el.innerHTML = el.dataset.src;
                          el.removeAttribute('data-processed');
                          el.classList.add('mermaid');
                        }
                        // Copy the diagram's Mermaid source to the clipboard (usable in mermaid.live etc.).
                        // Reads the cached source (data-src on inline stages, data-mmd on the modal clone).
                        function fallbackCopy(txt){
                          var ta=document.createElement('textarea'); ta.value=txt;
                          ta.style.position='fixed'; ta.style.opacity='0'; document.body.appendChild(ta); ta.select();
                          try{ document.execCommand('copy'); }catch(e){} document.body.removeChild(ta);
                        }
                        function copySource(pre, btn){
                          var txt = pre.dataset.src || pre.dataset.mmd || ''; if(!txt) return;
                          function ok(){ var g=btn.textContent; btn.textContent='\\u2713'; btn.title='copied';
                            setTimeout(function(){ btn.textContent=g; btn.title='copy Mermaid source'; }, 1000); }
                          if(navigator.clipboard && navigator.clipboard.writeText)
                            navigator.clipboard.writeText(txt).then(ok, function(){ fallbackCopy(txt); ok(); });
                          else { fallbackCopy(txt); ok(); }
                        }
                        // Pan/zoom core, shared by inline stages and the expand modal. Drag to pan;
                        // zoom via the +/- buttons (no wheel — the page scrolls a lot); fit, copy, plus one
                        // context button (expand inline / close in the modal). Pure CSS transform, no lib.
                        // State + apply/fit are stashed ON the element and refreshed on every call, so the
                        // once-bound drag listeners always drive the CURRENT svg (survives a theme re-render
                        // that swaps the svg, and a modal re-open that clones a new one).
                        function panzoom(pre, opts){
                          var svg = pre.querySelector('svg'); if(!svg) return;
                          pre.classList.add('pz');                 // fix the stage height before measuring
                          var vb = svg.viewBox && svg.viewBox.baseVal;
                          var w = vb && vb.width ? vb.width : svg.clientWidth;
                          var h = vb && vb.height ? vb.height : svg.clientHeight;
                          if(w){ svg.style.width = w+'px'; svg.style.height = h+'px'; }
                          var st = pre._pz || (pre._pz = {s:1,x:0,y:0});
                          function apply(){ svg.style.transform = 'translate('+st.x+'px,'+st.y+'px) scale('+st.s+')'; }
                          function fit(){
                            var cw = pre.clientWidth, ch = pre.clientHeight;
                            if(w && h){ st.s = Math.min(cw/w, ch/h, 1.5); st.x = (cw-w*st.s)/2; st.y = (ch-h*st.s)/2; }
                            else { st.s=1; st.x=0; st.y=0; }
                            apply();
                          }
                          function zoom(f, cx, cy){
                            var ns = Math.min(8, Math.max(0.1, st.s*f));
                            st.x = cx-(cx-st.x)*(ns/st.s); st.y = cy-(cy-st.y)*(ns/st.s); st.s = ns; apply();
                          }
                          pre._pzApply = apply; pre._pzFit = fit;   // refreshed per call, drive the current svg
                          fit();
                          // Controls are wiped when a theme re-render / modal-open resets innerHTML — rebuild if gone.
                          if(!pre.querySelector('.pz-ctl')){
                            var bar = document.createElement('div'); bar.className = 'pz-ctl';
                            var glyphs=['+','\\u2212','\\u2922','\\u29c9',opts.glyph];
                            var titles=['zoom in','zoom out','fit','copy Mermaid source',opts.title];
                            glyphs.forEach(function(lbl,i){
                              var b = document.createElement('button'); b.type='button'; b.textContent = lbl; b.title = titles[i];
                              b.addEventListener('click', function(e){ e.stopPropagation();
                                if(i===4){ opts.action(); return; }
                                if(i===3){ copySource(pre, b); return; }
                                var r = pre.getBoundingClientRect();
                                if(i===2) fit(); else zoom(i===0?1.25:0.8, r.width/2, r.height/2);
                              });
                              bar.appendChild(b);
                            });
                            pre.appendChild(bar);
                            var hint = document.createElement('div'); hint.className='pz-hint';
                            hint.textContent = 'drag to pan'; pre.appendChild(hint);
                          }
                          if(pre.dataset.pzBound) return; pre.dataset.pzBound = '1';
                          var drag=false, ox=0, oy=0;
                          pre.addEventListener('pointerdown', function(e){ if(e.target.closest('.pz-ctl')) return;
                            drag=true; ox=e.clientX-pre._pz.x; oy=e.clientY-pre._pz.y; pre.classList.add('grabbing');
                            try{ pre.setPointerCapture(e.pointerId); }catch(_){}
                          });
                          pre.addEventListener('pointermove', function(e){ if(!drag) return;
                            pre._pz.x=e.clientX-ox; pre._pz.y=e.clientY-oy; pre._pzApply();
                          });
                          function end(){ drag=false; pre.classList.remove('grabbing'); }
                          pre.addEventListener('pointerup', end);
                          pre.addEventListener('pointercancel', end);
                        }
                        // Inline stage: the 4th control expands the diagram into an in-page modal.
                        function enhance(pre){
                          if(!pre.querySelector('svg')) return;
                          panzoom(pre, {glyph:'\\u26f6', title:'expand', action:function(){ openModal(pre); }});
                        }
                        // Expand modal: a large in-page lightbox holding a fresh clone of the diagram
                        // with its own pan/zoom. Backdrop click or Esc closes it. Stays inside the page
                        // (no OS fullscreen), inheriting the current theme.
                        function modalEls(){
                          var o = document.getElementById('pz-modal');
                          if(!o){
                            o = document.createElement('div'); o.id = 'pz-modal';
                            var stage = document.createElement('pre'); stage.className = 'mermaid pz-modal-stage';
                            stage.id = 'pz-modal-stage'; o.appendChild(stage);
                            o.addEventListener('click', function(e){ if(e.target===o) closeModal(); });
                            document.body.appendChild(o);
                          }
                          return {overlay:o, stage:document.getElementById('pz-modal-stage')};
                        }
                        function openModal(srcPre){
                          var srcSvg = srcPre.querySelector('svg'); if(!srcSvg) return;
                          var m = modalEls();
                          m.stage.innerHTML = ''; m.stage._pz = null;      // fresh view each open
                          m.stage.dataset.mmd = srcPre.dataset.src || srcPre.dataset.mmd || '';  // for copy
                          var clone = srcSvg.cloneNode(true); clone.removeAttribute('style');
                          m.stage.appendChild(clone);
                          m.overlay.style.display = 'flex';
                          document.body.style.overflow = 'hidden';         // freeze page scroll behind
                          panzoom(m.stage, {glyph:'\\u2715', title:'close', action:closeModal});
                        }
                        function closeModal(){
                          var o = document.getElementById('pz-modal');
                          if(o) o.style.display = 'none';
                          document.body.style.overflow = '';
                        }
                        document.addEventListener('keydown', function(e){
                          if(e.key==='Escape'){ var o=document.getElementById('pz-modal'); if(o && o.style.display!=='none') closeModal(); }
                        });
                        // Render a batch in a SINGLE mermaid.run — concurrent per-element runs leave
                        // stray measuring SVGs that overlap other sections (mindmaps especially).
                        function renderBatch(nodes){
                          if(!nodes.length) return;
                          nodes.forEach(prep);
                          try {
                            var p = mermaid.run({nodes:nodes});
                            if(p && p.then) p.then(function(){ nodes.forEach(enhance); }, function(){});
                            else nodes.forEach(enhance);
                          } catch(e){}
                        }
                        window.addEventListener('load', function(){
                          // Top-level diagrams (ER, state, mindmap) are always visible — render together.
                          renderBatch([...document.querySelectorAll('pre.mermaid:not(.mermaid-lazy)')]);
                          // Sequence diagrams live inside collapsed <details>; render on first open
                          // (otherwise Mermaid measures a hidden 0-width element and draws nothing).
                          document.querySelectorAll('details.usecase').forEach(function(d){
                            d.addEventListener('toggle', function(){
                              if(!d.open) return;
                              var el=d.querySelector('pre.mermaid-lazy');
                              if(el && !el.dataset.rendered){ el.dataset.rendered='1'; renderBatch([el]); }
                            });
                          });
                          // Toggle button: flip theme, persist, re-init Mermaid and re-render live diagrams.
                          var btn=document.getElementById('theme-toggle');
                          if(btn) btn.addEventListener('click', function(){
                            var next = kindexTheme()==='dark' ? 'light' : 'dark';
                            document.documentElement.setAttribute('data-theme', next);
                            localStorage.setItem('kindex-theme', next);
                            mermaid.initialize({startOnLoad:false, theme: mermaidTheme(next)});
                            renderBatch([...document.querySelectorAll('pre.mermaid, pre.mermaid-lazy')].filter(function(el){ return el.dataset.src!==undefined; }));
                          });
                        });
                        </script>
                        """)
                .append("</head><body>\n");

        header(h, m, entities, entryPoints, callGraph, stateMachines, tests);
        useCaseSection(h, enr(m, "behaviors"), model(m, "behaviors"), flows);
        erSection(h, entities, migrations);
        stateSection(h, stateMachines, enr(m, "stateTransitions"), model(m, "stateTransitions"));
        domainSection(h, enr(m, "domains"), model(m, "domains"), callGraph, entities);
        testSection(h, tests);

        h.append("<footer>Generated deterministically from commit <code>")
                .append(esc(shortCommit(m.project().commit())))
                .append("</code>. Enrichment items are interpretations anchored to code evidence.</footer>\n");
        h.append("</body></html>\n");
        return h.toString();
    }

    // ---- header ---------------------------------------------------------------------------

    private void header(StringBuilder h, Manifest m, List<EntityModel> entities, List<EntryPoint> eps,
                        CallGraph cg, List<StateMachine> sms, List<TestUnit> tests) {
        int scenarios = tests.stream().mapToInt(t -> t.scenarios().size()).sum();
        h.append("<header>")
                .append("<button id=\"theme-toggle\" class=\"theme-toggle\" title=\"Toggle light/dark\" aria-label=\"Toggle theme\">◐</button>")
                .append("<h1>Knowledge Index</h1>")
                .append("<div class=\"meta\"><strong>").append(esc(m.project().name())).append("</strong>")
                .append(" · commit <code>").append(esc(shortCommit(m.project().commit()))).append("</code>")
                .append(" · ").append(esc(m.project().generatedAt())).append("</div>");
        String commitMessage = m.project().commitMessage();
        if (!commitMessage.isBlank()) {
            int nl = commitMessage.indexOf('\n');
            String subject = nl >= 0 ? commitMessage.substring(0, nl) : commitMessage;
            String body = nl >= 0 ? commitMessage.substring(nl + 1).strip() : "";
            if (body.isEmpty()) {
                h.append("<div class=\"commitbox\">").append(esc(subject)).append("</div>");
            } else {
                // Subject shown collapsed; the body (and any trailers) expand on click.
                h.append("<details class=\"commitbox\"><summary>").append(esc(subject))
                        .append("</summary><div class=\"commitbody\">").append(esc(body)).append("</div></details>");
            }
        }
        h.append("<div class=\"cards\">")
                .append(card(entities.size(), "entities"))
                .append(card(eps.size(), "entry points"))
                .append(card(cg == null ? 0 : cg.nodes().size(), "graph nodes"))
                .append(card(cg == null ? 0 : cg.edges().size(), "call edges"))
                .append(card(sms.size(), "state machines"))
                .append(card(scenarios, "test scenarios"))
                .append("</div>")
                .append("<div class=\"legend\"><span class=\"badge fact\">deterministic</span> extracted from the AST"
                        + " &nbsp; <span class=\"badge llm\">interpreted</span> LLM, hover for evidence</div>")
                .append("</header>\n");
    }

    private String card(int n, String label) {
        return "<div class=\"cardbox\"><div class=\"num\">" + n + "</div><div class=\"lbl\">" + esc(label) + "</div></div>";
    }

    // ---- use cases / flows (the headline business view) ----------------------------------

    private void useCaseSection(StringBuilder h, JsonNode useCases, String model, FlowContext flows) {
        List<EntryPoint> entryPoints = flows.entryPoints;
        h.append("<section><h2>Use Cases <span class=\"sub\">flows</span> ");
        boolean interpreted = useCases != null && useCases.isArray() && useCases.size() > 0 && !"none".equals(model);
        h.append(interpreted ? badgeLlm("Use cases interpreted by " + model + ", anchored to endpoints & tests") : badgeFact());
        h.append("</h2>\n");

        if (!interpreted) {
            h.append("<p class=\"empty\">No use cases generated yet — run the enrichment (agent flow or "
                    + "--provider sdk) to derive business flows from the endpoints.</p>");
            unclassifiedEndpoints(h, entryPoints, java.util.Set.of(), "All endpoints (no use cases yet)");
            h.append("</section>\n");
            return;
        }

        int total = useCases.size();
        int gaps = 0;
        int criticalGaps = 0;
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (JsonNode b : useCases) {
            covered.add(b.path("evidence").path("entryPoint").asText());
            b.path("evidence").path("covers").forEach(c -> covered.add(c.asText()));
            if (b.path("gap").asBoolean(false)) {
                gaps++;
                if ("critical".equals(b.path("priority").asText())) {
                    criticalGaps++;
                }
            }
        }
        h.append("<div class=\"cards\">")
                .append(card(total, "use cases"))
                .append(card(total - gaps, "verified by tests"))
                .append(card(gaps, "coverage gaps"))
                .append(card(criticalGaps, "critical gaps"))
                .append("</div>\n");

        int full = 0, partial = 0, thin = 0;
        for (JsonNode b : useCases) {
            switch (b.path("coverage").path("level").asText("")) {
                case "full" -> full++;
                case "partial" -> partial++;
                case "thin" -> thin++;
                default -> { }
            }
        }
        if (full + partial + thin > 0) {
            h.append("<div class=\"covroll\">Coverage estimate ").append(badgeLlm("LLM estimate per use case"))
                    .append(": <span class=\"cov cov-full\">").append(full).append(" full</span> · ")
                    .append("<span class=\"cov cov-partial\">").append(partial).append(" partial</span> · ")
                    .append("<span class=\"cov cov-thin\">").append(thin).append(" thin</span></div>\n");
        }

        // Group by feature (business capability), stable order.
        java.util.Map<String, java.util.List<JsonNode>> byFeature = new java.util.TreeMap<>();
        for (JsonNode b : useCases) {
            byFeature.computeIfAbsent(b.path("feature").asText("General"), k -> new java.util.ArrayList<>()).add(b);
        }
        for (var entry : byFeature.entrySet()) {
            h.append("<h3 class=\"feature\">").append(esc(entry.getKey())).append("</h3>\n");
            for (JsonNode b : entry.getValue()) {
                renderUseCase(h, b, flows);
            }
        }

        // Endpoints not (yet) described by any use case — replaces the old flat endpoint table.
        unclassifiedEndpoints(h, entryPoints, covered, "Endpoints without a use case");
        h.append("</section>\n");
    }

    /** One collapsible use-case card: summary closed, details on expand. */
    private void renderUseCase(StringBuilder h, JsonNode b, FlowContext flows) {
        boolean gap = b.path("gap").asBoolean(false);
        String priority = b.path("priority").asText("medium");
        JsonNode ev = b.path("evidence");
        String epId = ev.path("entryPoint").asText();
        String endpoint = stripHttp(epId);

        h.append("<details class=\"usecase").append(gap ? " gaprow" : "").append("\">");
        h.append("<summary>");
        h.append("<span class=\"scentitle\">").append(esc(b.path("scenario").asText())).append("</span>");
        h.append("<span class=\"endpoint\">").append(esc(endpoint)).append("</span>");
        h.append(tag("prio-" + priority, priority));
        h.append(tag("type", b.path("type").asText("")));
        h.append(gap
                ? "<span class=\"gap\">no coverage</span>"
                : "<span class=\"ok\">✓ " + b.path("verifiedBy").size() + " test(s)</span>");
        h.append("</summary>");

        h.append("<div class=\"uc-body\">");
        JsonNode coversNode = ev.path("covers");
        if (coversNode.isArray() && coversNode.size() > 0) {
            java.util.List<String> cs = new java.util.ArrayList<>();
            coversNode.forEach(c -> cs.add(stripHttp(c.asText())));
            java.util.Collections.sort(cs);
            h.append("<div class=\"uc-meta\"><strong>Also covers</strong> <span class=\"ev\">")
                    .append(esc(String.join(" · ", cs))).append("</span></div>");
        }
        h.append("<div class=\"gherkin\">")
                .append("<div><span class=\"kw\">Given</span> ").append(esc(b.path("given").asText(""))).append("</div>")
                .append("<div><span class=\"kw\">When</span> ").append(esc(b.path("when").asText(""))).append("</div>")
                .append("<div><span class=\"kw\">Then</span> ").append(esc(b.path("then").asText(""))).append("</div>")
                .append("</div>");

        // Preconditions — Bean Validation on the @RequestBody command + imperative guards reachable
        // in the flow (Guard.*, Objects.requireNonNull, Assert.*), both deterministic.
        List<InputConstraint> preconditions = flows.preconditions(epId);
        List<String[]> guards = flows.guards(epId);
        if (!preconditions.isEmpty() || !guards.isEmpty()) {
            h.append("<div class=\"uc-meta\"><strong>Preconditions</strong> ").append(badgeFact()).append("<ul class=\"tight\">");
            for (InputConstraint c : preconditions) {
                h.append("<li><code>").append(esc(c.field())).append("</code> ").append(esc(c.constraint()));
                if (c.detail() != null) {
                    h.append(" <span class=\"ev\">(").append(esc(c.detail())).append(")</span>");
                }
                h.append("</li>");
            }
            for (String[] g : guards) { // {field, constraint}
                h.append("<li><code>").append(esc(g[0])).append("</code> ").append(esc(g[1]))
                        .append(" <span class=\"ev\">(guard)</span></li>");
            }
            h.append("</ul></div>");
        }

        // Sequence diagram — built deterministically from the call graph rooted at this endpoint.
        String sequence = flows.sequence(epId);
        if (sequence != null && !sequence.isBlank()) {
            h.append("<div class=\"uc-meta\"><strong>Sequence</strong> ").append(badgeFact()).append("</div>");
            h.append("<pre class=\"mermaid-lazy\">\n").append(sequence).append("</pre>");
        }

        // Alternative flows — exceptions reachable in the flow, with their HTTP status (deterministic).
        List<String[]> altFlows = flows.alternativeFlows(epId);
        if (!altFlows.isEmpty()) {
            h.append("<div class=\"uc-meta\"><strong>Alternative flows</strong> ").append(badgeFact()).append("<ul class=\"tight\">");
            for (String[] a : altFlows) { // {exceptionType, status, detail}
                h.append("<li><code>").append(esc(a[0])).append("</code>");
                if (!a[1].isBlank() && !"—".equals(a[1])) {
                    h.append(" <span class=\"status\">→ ").append(esc(a[1])).append("</span>");
                }
                if (!a[2].isBlank()) {
                    h.append(" — <span class=\"ev\">").append(esc(a[2])).append("</span>");
                }
                h.append("</li>");
            }
            h.append("</ul></div>");
        }

        // Verified-by (deterministic link) + LLM coverage estimate over the flow's branches.
        if (gap) {
            h.append("<div class=\"uc-meta gap\"><strong>Coverage:</strong> no test exercises this flow</div>");
        } else {
            java.util.List<String> tests = new java.util.ArrayList<>();
            b.path("verifiedBy").forEach(v -> tests.add(v.asText()));
            h.append("<div class=\"uc-meta\"><strong>Verified by:</strong> <span class=\"ev\">")
                    .append(esc(String.join(", ", tests))).append("</span></div>");
        }
        String level = b.path("coverage").path("level").asText("");
        if (!level.isEmpty()) {
            h.append("<div class=\"uc-meta\"><strong>Coverage estimate</strong> ")
                    .append(badgeLlm("LLM estimate over the deterministic error/validation branches"))
                    .append(" <span class=\"cov cov-").append(esc(level)).append("\">").append(esc(level)).append("</span>");
            JsonNode untested = b.path("coverage").path("untested");
            if (untested.isArray() && untested.size() > 0) {
                h.append("<ul class=\"tight\">");
                for (JsonNode u : untested) {
                    h.append("<li>").append(esc(u.asText())).append("</li>");
                }
                h.append("</ul>");
            }
            h.append("</div>");
        }
        h.append("</div></details>\n");
    }

    /** List of entry points not covered by any use case. */
    private void unclassifiedEndpoints(StringBuilder h, List<EntryPoint> entryPoints, java.util.Set<String> covered, String title) {
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (EntryPoint ep : entryPoints) {
            if (!covered.contains(ep.id())) {
                rest.add(stripHttp(ep.id()));
            }
        }
        if (rest.isEmpty()) {
            return;
        }
        java.util.Collections.sort(rest);
        h.append("<details class=\"unclassified\"><summary>").append(esc(title))
                .append(" <span class=\"count\">").append(rest.size()).append("</span></summary><ul>");
        for (String ep : rest) {
            h.append("<li><code>").append(esc(ep)).append("</code></li>");
        }
        h.append("</ul></details>\n");
    }

    private static String stripHttp(String id) {
        return id == null ? "" : id.replaceFirst("^http:", "");
    }

    /** {@code br.com...OrderService#place} → {@code OrderService#place}. */
    private static String simpleNode(String id) {
        if (id == null) {
            return "";
        }
        int hash = id.indexOf('#');
        String cls = hash >= 0 ? id.substring(0, hash) : id;
        String method = hash >= 0 ? id.substring(hash) : "";
        int dot = cls.lastIndexOf('.');
        return (dot >= 0 ? cls.substring(dot + 1) : cls) + method;
    }

    // ---- deterministic sequence diagrams (from the call graph) ---------------------------

    private static final int MAX_SEQUENCE_MESSAGES = 40;

    /** One Mermaid sequenceDiagram per http endpoint, traced from the call graph. */
    private static Map<String, String> buildSequences(List<EntryPoint> eps, CallGraph cg, FlowContext ctx) {
        Map<String, String> out = new HashMap<>();
        if (cg == null) {
            return out;
        }
        Map<String, CallGraph.Node> byId = new HashMap<>();
        Map<String, String> keyToId = new HashMap<>(); // "Class#method" -> node id
        for (CallGraph.Node n : cg.nodes()) {
            byId.put(n.id(), n);
            keyToId.putIfAbsent(n.className() + "#" + n.method(), n.id());
        }
        Map<String, List<CallGraph.Edge>> adjacency = new HashMap<>();
        for (CallGraph.Edge e : cg.edges()) {
            adjacency.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
        }
        adjacency.values().forEach(l -> l.sort(Comparator.comparingInt(CallGraph.Edge::line)));

        for (EntryPoint ep : eps) {
            if (!"http".equals(ep.category())) {
                continue;
            }
            String rootId = keyToId.get(ep.className() + "#" + ep.method());
            if (rootId != null) {
                out.put(ep.id(), sequenceText(ep, rootId, byId, adjacency, ctx.alternativeFlows(ep.id())));
            }
        }
        return out;
    }

    private static String sequenceText(EntryPoint ep, String rootId, Map<String, CallGraph.Node> byId,
                                Map<String, List<CallGraph.Edge>> adjacency, List<String[]> altFlows) {
        LinkedHashSet<String> participants = new LinkedHashSet<>();
        List<String[]> messages = new ArrayList<>(); // {fromClass, toClass, method}
        String rootClass = byId.get(rootId).className();
        participants.add(rootClass);
        boolean[] truncated = {false};
        Set<String> expanded = new HashSet<>();
        expanded.add(rootId);
        traceCalls(rootId, byId, adjacency, participants, messages, expanded, new HashSet<>(), truncated);

        StringBuilder d = new StringBuilder("sequenceDiagram\n  actor Client\n");
        for (String p : participants) {
            d.append("  participant ").append(MermaidSafe.id(p)).append('\n');
        }
        d.append("  Client->>").append(MermaidSafe.id(rootClass)).append(": ")
                .append(MermaidSafe.label(stripHttp(ep.id()))).append('\n');
        for (String[] msg : messages) { // {from, to, label, arrow}
            d.append("  ").append(MermaidSafe.id(msg[0])).append(msg[3]).append(MermaidSafe.id(msg[1]))
                    .append(": ").append(MermaidSafe.label(msg[2])).append('\n');
        }
        if (truncated[0]) {
            d.append("  Note over ").append(MermaidSafe.id(rootClass)).append(": … sequence truncated\n");
        }
        // Final outcome — always returned to the Client by the controller (the HTTP boundary): either
        // the success response, or one of the reachable exceptions translated to an error status. Both
        // terminate at the Client, symmetric with the happy return.
        String ret = simpleReturn(byId.get(rootId).returnType());
        String ok = ret != null ? ret : "OK";
        String controller = MermaidSafe.id(rootClass);
        if (altFlows.isEmpty()) {
            d.append("  ").append(controller).append("-->>Client: ").append(MermaidSafe.label(ok)).append('\n');
        } else {
            // Reads as a decision: each error CONDITION is a guarded alt/else branch; the happy path
            // is the default (final "else success"). Matches how the guards are actually evaluated.
            for (int i = 0; i < altFlows.size(); i++) {
                String[] a = altFlows.get(i); // {exceptionType, status, detail, throwingClass}
                String cond = (a[2] != null && !a[2].isBlank()) ? a[2] : a[0];
                if (cond.startsWith("when ")) {
                    cond = cond.substring(5);
                }
                String status = "—".equals(a[1]) ? "error" : a[1];
                d.append("  ").append(i == 0 ? "alt " : "else ").append(MermaidSafe.label(cond)).append('\n');
                d.append("    ").append(controller).append("--xClient: ")
                        .append(MermaidSafe.label(status + " (" + a[0] + ")")).append('\n');
            }
            d.append("  else success\n");
            d.append("    ").append(controller).append("-->>Client: ").append(MermaidSafe.label(ok)).append('\n');
            d.append("  end\n");
        }
        return d.toString();
    }

    /**
     * Depth-first walk of resolved calls, ordered by call-site line; bounded and cycle-safe.
     * Identical interactions (same caller→callee.method) are shown once — a method called in a loop
     * or a guard invoked repeatedly collapses to a single message, so the diagram reads as the set of
     * distinct interactions and doesn't truncate on repetition.
     */
    private static void traceCalls(String nodeId, Map<String, CallGraph.Node> byId,
                            Map<String, List<CallGraph.Edge>> adjacency, LinkedHashSet<String> participants,
                            List<String[]> messages, Set<String> expanded, Set<String> seenMessages, boolean[] truncated) {
        for (CallGraph.Edge e : adjacency.getOrDefault(nodeId, List.of())) {
            if (messages.size() >= MAX_SEQUENCE_MESSAGES) {
                truncated[0] = true;
                return;
            }
            CallGraph.Node from = byId.get(e.from());
            CallGraph.Node to = byId.get(e.to());
            if (from == null || to == null) {
                continue;
            }
            String key = from.className() + "->" + to.className() + "#" + to.method();
            if (!seenMessages.add(key)) {
                continue; // identical interaction already shown (loop / repeated guard)
            }
            participants.add(to.className());
            messages.add(new String[]{from.className(), to.className(), to.method(), "->>"}); // call
            if (expanded.add(e.to())) {
                traceCalls(e.to(), byId, adjacency, participants, messages, expanded, seenMessages, truncated);
            }
            String ret = simpleReturn(to.returnType()); // dashed return, after the callee's subtree
            if (ret != null) {
                messages.add(new String[]{to.className(), from.className(), ret, "-->>"});
            }
        }
    }

    /** Return-type label for a sequence return arrow; null for void/unknown (no arrow drawn). */
    private static String simpleReturn(String returnType) {
        if (returnType == null) {
            return null;
        }
        String t = returnType;
        int lt = t.indexOf('<');
        if (lt >= 0) {
            t = t.substring(0, lt); // drop generics: Optional<Sale> -> Optional
        }
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            t = t.substring(dot + 1);
        }
        t = t.trim();
        return t.isEmpty() || t.equals("void") ? null : t;
    }

    /** All node ids reachable from each http endpoint (unbounded) — used to scope alternative flows. */
    private static Map<String, Set<String>> reachableSets(List<EntryPoint> eps, CallGraph cg) {
        Map<String, Set<String>> out = new HashMap<>();
        if (cg == null) {
            return out;
        }
        Map<String, String> keyToId = new HashMap<>();
        for (CallGraph.Node n : cg.nodes()) {
            keyToId.putIfAbsent(n.className() + "#" + n.method(), n.id());
        }
        Map<String, List<CallGraph.Edge>> adjacency = new HashMap<>();
        for (CallGraph.Edge e : cg.edges()) {
            adjacency.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
        }
        for (EntryPoint ep : eps) {
            if (!"http".equals(ep.category())) {
                continue;
            }
            String rootId = keyToId.get(ep.className() + "#" + ep.method());
            if (rootId == null) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            java.util.Deque<String> queue = new java.util.ArrayDeque<>();
            seen.add(rootId);
            queue.add(rootId);
            while (!queue.isEmpty()) {
                for (CallGraph.Edge e : adjacency.getOrDefault(queue.poll(), List.of())) {
                    if (seen.add(e.to())) {
                        queue.add(e.to());
                    }
                }
            }
            out.put(ep.id(), seen);
        }
        return out;
    }

    /**
     * Per-endpoint flow facts precomputed once: the sequence diagram, the reachable node set, the
     * validation preconditions and the exception→status map. All deterministic; the use-case card
     * reads from here so alternative flows and preconditions stay scoped to the right endpoint.
     */
    static final class FlowContext {
        final List<EntryPoint> entryPoints;
        private final Map<String, String> sequences;
        private final Map<String, Set<String>> reachable;
        private final Map<String, List<InputConstraint>> constraintsByEp = new HashMap<>();
        private final Map<String, String> statusByType = new HashMap<>();
        private final List<ThrowSite> throwSites;
        private final List<GuardCheck> guardChecks;

        FlowContext(List<EntryPoint> entryPoints, CallGraph cg, List<ThrowSite> throwSites,
                    List<ExceptionStatus> statuses, List<InputConstraint> constraints, List<GuardCheck> guardChecks) {
            this.entryPoints = entryPoints;
            this.throwSites = throwSites;
            this.guardChecks = guardChecks;
            this.reachable = reachableSets(entryPoints, cg);
            for (InputConstraint c : constraints) {
                constraintsByEp.computeIfAbsent(c.entryPointId(), k -> new ArrayList<>()).add(c);
            }
            for (ExceptionStatus s : statuses) {
                statusByType.putIfAbsent(s.exceptionType(), s.status());
            }
            // Built last: the diagram embeds this flow's alternative (error) branches as alt blocks.
            this.sequences = buildSequences(entryPoints, cg, this);
        }

        String sequence(String epId) {
            return sequences.get(epId);
        }

        List<InputConstraint> preconditions(String epId) {
            return constraintsByEp.getOrDefault(epId, List.of());
        }

        /** Reachable guard/assertion checks in the flow, deduped by field+constraint: {field, constraint}. */
        List<String[]> guards(String epId) {
            Set<String> nodes = reachable.getOrDefault(epId, Set.of());
            Map<String, String[]> byKey = new java.util.LinkedHashMap<>();
            for (GuardCheck g : guardChecks) {
                if (!nodes.contains(g.nodeId())) {
                    continue;
                }
                byKey.putIfAbsent(g.field() + "|" + g.constraint(), new String[]{g.field(), g.constraint()});
            }
            List<String[]> out = new ArrayList<>(byKey.values());
            out.sort(Comparator.comparing(a -> a[0]));
            return out;
        }

        /** Exceptions reachable in the flow, deduped by type: {exceptionType, status, detail, throwingClass}. */
        List<String[]> alternativeFlows(String epId) {
            Set<String> nodes = reachable.getOrDefault(epId, Set.of());
            Map<String, String[]> byType = new java.util.TreeMap<>();
            for (ThrowSite t : throwSites) {
                if (!nodes.contains(t.nodeId())) {
                    continue;
                }
                byType.computeIfAbsent(t.exceptionType(), k -> new String[]{
                        t.exceptionType(),
                        statusByType.getOrDefault(t.exceptionType(), "—"),
                        detail(t),
                        t.className()});
            }
            return new ArrayList<>(byType.values());
        }

        private static String detail(ThrowSite t) {
            if (t.condition() != null && !t.condition().isBlank()) {
                return "when " + t.condition();
            }
            return t.message() != null ? t.message() : "";
        }
    }

    private String tag(String cls, String text) {
        return "<span class=\"tag " + cls + "\">" + esc(text) + "</span>";
    }

    // ---- ER -------------------------------------------------------------------------------

    private void erSection(StringBuilder h, List<EntityModel> entities, List<MigrationFk> migrations) {
        h.append("<section><h2>Entity–Relationship <span class=\"badge fact\">deterministic</span></h2>\n");
        if (entities.isEmpty()) {
            h.append("<p class=\"empty\">No JPA entities found.</p></section>\n");
            return;
        }
        // Group by module (package segment after the common base): a modular monolith with few
        // relations otherwise renders one unreadable strip. One small erDiagram per bounded context.
        Map<String, List<EntityModel>> byModule = groupByModule(entities);
        if (byModule.size() <= 1) {
            h.append(mermaid(erDiagramFor(entities, entities)));
        } else {
            h.append("<p class=\"note\">Grouped by module (").append(byModule.size())
                    .append(" bounded contexts); relations are shown within a module.</p>\n");
            for (var entry : byModule.entrySet()) {
                h.append("<h3 class=\"feature\">").append(esc(entry.getKey()))
                        .append(" <span class=\"ev\">").append(entry.getValue().size()).append(" entities</span></h3>\n");
                h.append(mermaid(erDiagramFor(entry.getValue(), entry.getValue())));
            }
        }
        if (!migrations.isEmpty()) {
            h.append("<p class=\"note\">Migration-declared foreign keys (best-effort):</p><ul>");
            for (MigrationFk fk : migrations) {
                h.append("<li><code>").append(esc(fk.table())).append('.').append(esc(fk.column()))
                        .append("</code> → <code>").append(esc(fk.refTable())).append('.').append(esc(fk.refColumn()))
                        .append("</code></li>");
            }
            h.append("</ul>");
        }
        h.append("</section>\n");
    }

    /** An erDiagram for {@code entities}; relations drawn only to targets within {@code relationScope}. */
    private String erDiagramFor(List<EntityModel> entities, List<EntityModel> relationScope) {
        Set<String> names = new HashSet<>();
        relationScope.forEach(e -> names.add(e.name()));
        StringBuilder d = new StringBuilder("erDiagram\n");
        for (EntityModel e : entities) {
            d.append("  ").append(MermaidSafe.id(e.name())).append(" {\n");
            for (FieldModel f : e.fields()) {
                d.append("    ").append(MermaidSafe.type(f.type())).append(' ').append(MermaidSafe.id(f.name()));
                if (f.id()) {
                    d.append(" PK");
                }
                d.append('\n');
            }
            d.append("  }\n");
        }
        for (EntityModel e : entities) {
            for (RelationModel r : e.relations()) {
                if (names.contains(r.target())) {
                    d.append("  ").append(MermaidSafe.id(e.name())).append(' ').append(cardinality(r.kind()))
                            .append(' ').append(MermaidSafe.id(r.target())).append(" : \"")
                            .append(MermaidSafe.label(r.field())).append("\"\n");
                }
            }
        }
        return d.toString();
    }

    /** Group entities by module = the package segment right after the base shared by all of them. */
    private static Map<String, List<EntityModel>> groupByModule(List<EntityModel> entities) {
        List<String[]> dirs = new ArrayList<>();
        for (EntityModel e : entities) {
            dirs.add(dirSegments(e.file()));
        }
        int cp = commonPrefixLen(dirs);
        Map<String, List<EntityModel>> byModule = new java.util.TreeMap<>();
        for (int i = 0; i < entities.size(); i++) {
            String[] segs = dirs.get(i);
            String module = segs.length > cp ? segs[cp] : "core";
            byModule.computeIfAbsent(module, k -> new ArrayList<>()).add(entities.get(i));
        }
        return byModule;
    }

    private static String[] dirSegments(String file) {
        if (file == null) {
            return new String[0];
        }
        String f = file.replace('\\', '/');
        int slash = f.lastIndexOf('/');
        String dir = slash >= 0 ? f.substring(0, slash) : "";
        return dir.isEmpty() ? new String[0] : dir.split("/");
    }

    private static int commonPrefixLen(List<String[]> paths) {
        if (paths.isEmpty()) {
            return 0;
        }
        int min = paths.stream().mapToInt(p -> p.length).min().orElse(0);
        int cp = 0;
        for (; cp < min; cp++) {
            String seg = paths.get(0)[cp];
            for (String[] p : paths) {
                if (!p[cp].equals(seg)) {
                    return cp;
                }
            }
        }
        return cp;
    }

    private static String cardinality(String kind) {
        return switch (kind) {
            case "OneToMany" -> "||--o{";
            case "ManyToOne" -> "}o--||";
            case "OneToOne" -> "||--||";
            case "ManyToMany" -> "}o--o{";
            default -> "||..||";
        };
    }

    // ---- state machines -------------------------------------------------------------------

    private void stateSection(StringBuilder h, List<StateMachine> machines, JsonNode transitions, String model) {
        h.append("<section><h2>State Machines</h2>\n");
        if (machines.isEmpty()) {
            h.append("<p class=\"empty\">No status/state enum detected — nothing to model. "
                    + "(Deterministic degradation: the section is simply empty.)</p></section>\n");
            return;
        }
        for (StateMachine sm : machines) {
            List<JsonNode> forEnum = transitionsFor(transitions, sm);
            h.append("<h3>").append(esc(sm.enumType()));
            if (!forEnum.isEmpty() && !"none".equals(model)) {
                h.append(' ').append(badgeLlm("Transitions inferred by " + model));
            } else {
                h.append(' ').append(badgeFact());
            }
            h.append("</h3>\n");

            if (!forEnum.isEmpty()) {
                StringBuilder d = new StringBuilder("stateDiagram-v2\n");
                d.append("  [*] --> ").append(MermaidSafe.id(initialState(sm))).append('\n');
                for (JsonNode t : forEnum) {
                    d.append("  ").append(MermaidSafe.id(t.path("from").asText()))
                            .append(" --> ").append(MermaidSafe.id(t.path("to").asText()))
                            .append(" : ").append(MermaidSafe.label(t.path("trigger").asText("")))
                            .append('\n');
                }
                h.append(mermaid(d.toString()));
                h.append("<p class=\"note\">Evidence:</p><ul>");
                for (JsonNode t : forEnum) {
                    JsonNode ev = t.path("evidence");
                    h.append("<li><code>").append(esc(t.path("from").asText())).append(" → ")
                            .append(esc(t.path("to").asText())).append("</code> — ")
                            .append(esc(t.path("trigger").asText(""))).append(" <span class=\"ev\">")
                            .append(esc(ev.path("class").asText(""))).append('#').append(esc(ev.path("method").asText("")))
                            .append(" (").append(esc(ev.path("file").asText(""))).append(':').append(ev.path("line").asInt())
                            .append(")</span></li>");
                }
                h.append("</ul>");
            } else {
                h.append("<p class=\"note\">States (deterministic): ");
                h.append(String.join(", ", sm.states().stream().map(this::esc).toList()));
                h.append("</p><p class=\"note\">Assignment sites:</p><ul>");
                for (CallGraph.AssignmentSite s : sm.assignmentSites()) {
                    h.append("<li><code>").append(esc(s.value())).append("</code> at ")
                            .append(esc(nullToDash(s.className()))).append('#').append(esc(nullToDash(s.method())))
                            .append(" <span class=\"ev\">").append(esc(s.file())).append(':').append(s.line())
                            .append("</span></li>");
                }
                h.append("</ul>");
            }
        }
        h.append("</section>\n");
    }

    /** Initial state = the value set at a constructor/initializer site (method == null), else first. */
    private String initialState(StateMachine sm) {
        for (CallGraph.AssignmentSite s : sm.assignmentSites()) {
            if (s.method() == null && s.value() != null) {
                return s.value();
            }
        }
        return sm.states().isEmpty() ? "start" : sm.states().get(0);
    }

    private List<JsonNode> transitionsFor(JsonNode transitions, StateMachine sm) {
        // Transitions carry evidence; keep those whose evidence file matches one of this enum's sites.
        Set<String> siteFiles = new HashSet<>();
        sm.assignmentSites().forEach(s -> siteFiles.add(s.file() + ":" + s.line()));
        List<JsonNode> out = new java.util.ArrayList<>();
        if (transitions != null && transitions.isArray()) {
            for (JsonNode t : transitions) {
                JsonNode ev = t.path("evidence");
                String key = ev.path("file").asText("") + ":" + ev.path("line").asInt(-1);
                if (siteFiles.contains(key)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    // ---- domains (mind map) --------------------------------------------------------------

    private void domainSection(StringBuilder h, JsonNode domains, String model, CallGraph cg, List<EntityModel> entities) {
        h.append("<section><h2>Domain Map ");
        boolean interpreted = domains != null && domains.isArray() && domains.size() > 0 && !"none".equals(model);
        h.append(interpreted ? badgeLlm("Grouping by " + model) : badgeFact()).append("</h2>\n");

        StringBuilder d = new StringBuilder("mindmap\n  root((").append(MermaidSafe.mindmapText("domains")).append("))\n");
        if (interpreted) {
            for (JsonNode dom : domains) {
                d.append("    ").append(MermaidSafe.mindmapText(dom.path("domain").asText("domain"))).append('\n');
                for (JsonNode member : dom.path("members")) {
                    d.append("      ").append(MermaidSafe.mindmapText(member.asText())).append('\n');
                }
            }
        } else {
            // Deterministic fallback: group by architectural role.
            Map<String, java.util.TreeSet<String>> byRole = new java.util.TreeMap<>();
            if (cg != null) {
                for (CallGraph.Node n : cg.nodes()) {
                    byRole.computeIfAbsent(n.role(), k -> new java.util.TreeSet<>()).add(n.className());
                }
            }
            entities.forEach(e -> byRole.computeIfAbsent("domain", k -> new java.util.TreeSet<>()).add(e.name()));
            byRole.forEach((role, members) -> {
                d.append("    ").append(MermaidSafe.mindmapText(role)).append('\n');
                members.forEach(mem -> d.append("      ").append(MermaidSafe.mindmapText(mem)).append('\n'));
            });
        }
        h.append(mermaid(d.toString()));
        if (interpreted) {
            h.append("<p class=\"note\">Rationale:</p><ul>");
            for (JsonNode dom : domains) {
                h.append("<li><strong>").append(esc(dom.path("domain").asText())).append("</strong>: ")
                        .append(esc(dom.path("rationale").asText(""))).append("</li>");
            }
            h.append("</ul>");
        }
        h.append("</section>\n");
    }

    // ---- tests ----------------------------------------------------------------------------

    private void testSection(StringBuilder h, List<TestUnit> tests) {
        h.append("<section><h2>Test Catalog <span class=\"badge fact\">deterministic</span></h2>\n");
        if (tests.isEmpty()) {
            h.append("<p class=\"empty\">No test scenarios found.</p></section>\n");
            return;
        }
        // Collapsed by default — each test's behaviour already shows in its use-case card
        // ("Verified by"). This is the full reference index as one organized table.
        int total = tests.stream().mapToInt(t -> t.scenarios().size()).sum();
        h.append("<details class=\"unclassified\"><summary>Full test index — ")
                .append(tests.size()).append(" classes, ").append(total).append(" scenarios</summary>\n");
        h.append("<table><thead><tr><th>Test class</th><th>Target</th><th>#</th><th>Scenarios</th></tr></thead><tbody>\n");
        for (TestUnit t : tests) {
            h.append("<tr><td><code>").append(esc(t.testClass())).append("</code></td><td>");
            if (t.targetClass() != null) {
                h.append("<span class=\"target").append(t.targetCertain() ? "" : " uncertain").append("\">")
                        .append(esc(t.targetClass())).append(t.targetCertain() ? "" : " (?)").append("</span>");
            } else {
                h.append("<span class=\"unknown\">—</span>");
            }
            h.append("</td><td>").append(t.scenarios().size()).append("</td><td><ul class=\"tight\">");
            for (Scenario s : t.scenarios()) {
                h.append("<li>").append(esc(s.description()))
                        .append(" <span class=\"ev\">").append(esc(s.method())).append("</span></li>");
            }
            h.append("</ul></td></tr>\n");
        }
        h.append("</tbody></table></details></section>\n");
    }

    // ---- helpers --------------------------------------------------------------------------

    private JsonNode enr(Manifest m, String key) {
        Manifest.EnrichmentSection s = m.enrichment().get(key);
        return s == null ? null : (JsonNode) s.data();
    }

    private String model(Manifest m, String key) {
        Manifest.EnrichmentSection s = m.enrichment().get(key);
        return s == null ? "none" : s.model();
    }

    private String mermaid(String diagram) {
        return "<pre class=\"mermaid\">\n" + diagram + "</pre>\n";
    }

    private String badgeLlm(String tip) {
        return "<span class=\"badge llm\" title=\"" + esc(tip) + "\">interpreted</span>";
    }

    private String badgeFact() {
        return "<span class=\"badge fact\">deterministic</span>";
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String shortCommit(String commit) {
        if (commit == null) {
            return "unknown";
        }
        return commit.length() > 10 ? commit.substring(0, 10) : commit;
    }

    private String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String css() {
        return """
                <style>
                :root{--fg:#1f2328;--muted:#656d76;--line:#d0d7de;--bg:#fff;--accent:#0969da;
                      --fact:#1a7f37;--llm:#8250df;--gap:#cf222e;--card:#f6f8fa;}
                :root[data-theme="dark"]{--fg:#e6edf3;--muted:#8b949e;--line:#30363d;--bg:#0d1117;
                      --accent:#58a6ff;--fact:#3fb950;--llm:#bc8cff;--gap:#f85149;--card:#161b22;}
                *{box-sizing:border-box}
                .theme-toggle{position:absolute;top:1rem;right:1.2rem;width:2rem;height:2rem;cursor:pointer;
                     background:var(--card);border:1px solid var(--line);color:var(--fg);border-radius:6px;font-size:1rem;line-height:1}
                .theme-toggle:hover{border-color:var(--accent)}
                body{margin:0;font:15px/1.55 -apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;
                     color:var(--fg);background:var(--bg);padding:0 0 4rem}
                header,section,footer{max-width:1080px;margin:0 auto;padding:1.2rem 1.5rem}
                header{border-bottom:1px solid var(--line);position:relative}
                h1{margin:.2rem 0;font-size:1.6rem}
                h2{margin:1.6rem 0 .6rem;font-size:1.25rem;border-bottom:1px solid var(--line);padding-bottom:.3rem}
                h3{margin:1.1rem 0 .4rem;font-size:1.02rem}
                .meta{color:var(--muted);margin-bottom:.6rem}
                .commitbox{margin:.2rem 0 .9rem;padding:.5rem .8rem;background:var(--card);border:1px solid var(--line);
                     border-left:3px solid var(--accent);border-radius:6px;word-break:break-word;
                     font:.85rem/1.45 ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--fg);overflow-x:auto;max-width:100%}
                div.commitbox{white-space:pre-wrap}
                details.commitbox>summary{cursor:pointer;font-weight:600;white-space:pre-wrap;word-break:break-word}
                details.commitbox>summary:hover{color:var(--accent)}
                .commitbody{white-space:pre-wrap;margin-top:.5rem;padding-top:.5rem;border-top:1px dashed var(--line);color:var(--muted)}
                .cards{display:flex;flex-wrap:wrap;gap:.6rem;margin:.4rem 0}
                .cardbox{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:.5rem .9rem;min-width:88px}
                .num{font-size:1.5rem;font-weight:700}
                .lbl{color:var(--muted);font-size:.8rem}
                code{background:var(--card);padding:.05rem .3rem;border-radius:4px;font-size:.86em}
                .badge{display:inline-block;font-size:.7rem;font-weight:700;text-transform:uppercase;letter-spacing:.03em;
                       padding:.1rem .4rem;border-radius:10px;vertical-align:middle}
                .badge.fact{background:#dafbe1;color:var(--fact)}
                .badge.llm{background:#fbefff;color:var(--llm);cursor:help;border:1px solid #e5c9ff}
                .legend{color:var(--muted);font-size:.85rem;margin-top:.6rem}
                pre.mermaid,pre.mermaid-lazy{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:1rem;overflow:auto;text-align:center;max-height:70vh}
                /* Contain the rendered SVG so a wide diagram scrolls inside its box instead of
                   overflowing and overlapping neighbouring sections. */
                pre.mermaid svg,pre.mermaid-lazy svg{max-width:100%;height:auto}
                pre.mermaid-lazy{font-size:.75rem;color:var(--muted);text-align:left}
                pre.mermaid-lazy.mermaid{text-align:center}
                /* Pan/zoom stage: once rendered, a diagram becomes a fixed-height viewport the
                   SVG is dragged/zoomed inside (CSS transform, driven by the head script — no lib). */
                pre.mermaid.pz,pre.mermaid-lazy.pz{position:relative;overflow:hidden;padding:0;cursor:grab;
                     height:clamp(340px,60vh,640px);max-height:none;text-align:left;touch-action:none}
                pre.mermaid.pz.grabbing,pre.mermaid-lazy.pz.grabbing{cursor:grabbing}
                /* In-page expand modal (lightbox) — inherits the current theme, closes on backdrop/Esc. */
                #pz-modal{position:fixed;inset:0;z-index:1000;display:none;align-items:center;justify-content:center;
                     background:rgba(0,0,0,.55);padding:2.5vh 2vw}
                #pz-modal>pre.pz-modal-stage{width:96vw;height:95vh;max-height:none;margin:0;background:var(--card);
                     border:1px solid var(--line);border-radius:10px;box-shadow:0 12px 48px rgba(0,0,0,.45)}
                pre.mermaid.pz svg,pre.mermaid-lazy.pz svg{max-width:none;transform-origin:0 0;will-change:transform;user-select:none}
                .pz-ctl{position:absolute;top:8px;right:8px;display:flex;gap:4px;z-index:5}
                .pz-ctl button{width:28px;height:28px;padding:0;border:1px solid var(--line);background:var(--card);
                     color:var(--fg);border-radius:6px;cursor:pointer;font-size:15px;line-height:1;
                     display:flex;align-items:center;justify-content:center}
                .pz-ctl button:hover{border-color:var(--accent);color:var(--accent)}
                .pz-hint{position:absolute;bottom:6px;left:10px;font-size:.68rem;color:var(--muted);
                     pointer-events:none;opacity:.65;user-select:none}
                table{border-collapse:collapse;width:100%;font-size:.9rem;margin:.4rem 0}
                th,td{border:1px solid var(--line);padding:.4rem .6rem;text-align:left;vertical-align:top}
                th{background:var(--card)}
                .ev{color:var(--muted);font-size:.78rem}
                .gap{color:var(--gap);font-weight:700}
                .ok{color:var(--fact);font-weight:700}
                .unknown{color:var(--muted)}
                .empty,.note{color:var(--muted)}
                .target{color:var(--accent);font-size:.85rem}
                .target.uncertain{color:var(--muted)}
                ul{margin:.3rem 0 .8rem;padding-left:1.3rem}
                li{margin:.15rem 0}
                footer{color:var(--muted);font-size:.85rem;border-top:1px solid var(--line);margin-top:2rem}
                .sub{color:var(--muted);font-weight:400;font-size:.9rem}
                h3.feature{color:var(--accent);border-bottom:1px dashed var(--line);padding-bottom:.2rem}
                .scenario{border:1px solid var(--line);border-left:3px solid var(--accent);border-radius:6px;
                          padding:.5rem .7rem;margin:.5rem 0;background:#fff}
                .scenario.gaprow{border-left-color:var(--gap);background:#fff8f8}
                .scentitle{font-weight:600;margin-bottom:.3rem}
                .gherkin{font-size:.9rem;color:#374151;margin:.2rem 0}
                .gherkin .kw{color:var(--llm);font-weight:700}
                .tag{display:inline-block;font-size:.68rem;font-weight:700;text-transform:uppercase;
                     padding:.05rem .35rem;border-radius:8px;vertical-align:middle;margin-left:.2rem}
                .tag.type{background:#eef2ff;color:#3730a3}
                .tag.prio-critical{background:#ffe3e3;color:#b91c1c}
                .tag.prio-high{background:#fff0d6;color:#b45309}
                .tag.prio-medium{background:#eef1f4;color:#475569}
                details.usecase{border:1px solid var(--line);border-left:3px solid var(--accent);border-radius:6px;
                                margin:.4rem 0;background:var(--bg)}
                details.usecase.gaprow{border-left-color:var(--gap);background:#fff8f8}
                details.usecase>summary{cursor:pointer;list-style:none;padding:.5rem .7rem;display:flex;
                                        flex-wrap:wrap;align-items:center;gap:.4rem}
                details.usecase>summary::-webkit-details-marker{display:none}
                details.usecase>summary::before{content:"▸";color:var(--muted);font-size:.8rem}
                details.usecase[open]>summary::before{content:"▾"}
                details.usecase>summary:hover{background:var(--card)}
                .scentitle{font-weight:600}
                .endpoint{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.78rem;color:var(--accent);
                          background:#eef4ff;padding:.05rem .35rem;border-radius:4px}
                .uc-body{padding:.1rem .9rem .7rem 1.4rem;border-top:1px dashed var(--line)}
                .gherkin{font-size:.92rem;color:var(--fg);margin:.5rem 0}
                .gherkin>div{margin:.1rem 0}
                .gherkin .kw{display:inline-block;min-width:3.4rem;color:var(--llm);font-weight:700}
                .uc-meta{font-size:.85rem;margin:.4rem 0}
                .uc-meta.gap{color:var(--gap)}
                ul.tight{margin:.2rem 0 .2rem .2rem;padding-left:1.1rem}
                ul.tight li{margin:.05rem 0;font-size:.85rem}
                .status{color:var(--gap);font-weight:700;font-size:.8rem}
                .cov{display:inline-block;font-size:.72rem;font-weight:700;text-transform:uppercase;
                     padding:.05rem .4rem;border-radius:8px}
                .cov-full{background:#dafbe1;color:var(--fact)}
                .cov-partial{background:#fff0d6;color:#b45309}
                .cov-thin{background:#ffe3e3;color:#b91c1c}
                .covroll{margin:.3rem 0 .2rem;font-size:.9rem;color:var(--muted)}
                details.unclassified{margin:1rem 0;border:1px dashed var(--line);border-radius:6px;padding:.3rem .7rem;background:var(--card)}
                details.unclassified>summary{cursor:pointer;color:var(--muted);font-size:.9rem}
                .count{background:var(--gap);color:#fff;border-radius:10px;padding:0 .4rem;font-size:.75rem;font-weight:700}
                /* Dark-mode chip overrides: translucent fills keep the semantic hue readable on dark. */
                [data-theme="dark"] .badge.fact{background:rgba(63,185,80,.16)}
                [data-theme="dark"] .badge.llm{background:rgba(188,140,255,.16);border-color:#3b2d52}
                [data-theme="dark"] .tag.type{background:rgba(88,166,255,.16);color:#a9c7ff}
                [data-theme="dark"] .tag.prio-critical{background:rgba(248,81,73,.16);color:#ff9a92}
                [data-theme="dark"] .tag.prio-high{background:rgba(210,153,34,.2);color:#e3b341}
                [data-theme="dark"] .tag.prio-medium{background:rgba(139,148,158,.2);color:#adbac7}
                [data-theme="dark"] .cov-full{background:rgba(63,185,80,.16);color:#3fb950}
                [data-theme="dark"] .cov-partial{background:rgba(210,153,34,.2);color:#e3b341}
                [data-theme="dark"] .cov-thin{background:rgba(248,81,73,.16);color:#ff9a92}
                [data-theme="dark"] .endpoint{background:rgba(88,166,255,.14)}
                [data-theme="dark"] details.usecase.gaprow{background:rgba(248,81,73,.07)}
                </style>
                """;
    }
}
