package sdlc.workflow;

import sdlc.core.Console;

/**
 * The workflow steps that talk to the issue tracker and the wiki, wired together in one place.
 * Each step gets the tracker and the wiki as interfaces through this object, so the steps do
 * not know that they are Jira and Confluence: Main plugs those in, a test plugs in fakes.
 * The same goes for the console the steps write to.
 */
public final class Workflow {
    public final IssueTracker issues;
    public final Wiki wiki;
    public final Console console;
    public final Docs docs;
    public final Approvals approvals;
    public final Tasks tasks;
    public final Sync sync;
    public final Eval eval;
    public final Stories stories;
    public final Feature feature;
    public final Holdout holdout;
    public final CoChange cochange;

    public Workflow(IssueTracker issues, Wiki wiki, Console console) {
        this.issues = issues;
        this.wiki = wiki;
        this.console = console;
        this.docs = new Docs(this);
        this.approvals = new Approvals(this);
        this.tasks = new Tasks(this);
        this.sync = new Sync(issues, console);
        this.eval = new Eval(this);
        this.stories = new Stories(this);
        this.feature = new Feature(this);
        this.holdout = new Holdout(this);
        this.cochange = new CoChange(this);
    }
}
