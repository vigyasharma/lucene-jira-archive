#
# Convert Jira issues to GitHub issues for Import Issues API (https://gist.github.com/jonmagic/5282384165e0f86ef105)
# Usage:
#   python src/jira2github_import.py --issues <issue number list>
#   python src/jira2github_import.py --min <min issue number> --max <max issue number>
#

import argparse
from pathlib import Path
import json
import sys
from urllib.parse import quote
import os

from common import LOG_DIRNAME, JIRA_DUMP_DIRNAME, GITHUB_IMPORT_DATA_DIRNAME, MAPPINGS_DATA_DIRNAME, ACCOUNT_MAPPING_FILENAME, ISSUE_TYPE_TO_LABEL_MAP, COMPONENT_TO_LABEL_MAP, \
    logging_setup, jira_issue_url, jira_dump_file, jira_issue_id, github_data_file, make_github_title, read_account_map
from jira_util import *

log_dir = Path(__file__).resolve().parent.parent.joinpath(LOG_DIRNAME)
logger = logging_setup(log_dir, "jira2github_import")


def attachment_url(issue_num: int, filename: str, att_repo: str, att_branch: str) -> str:
    return f"https://raw.githubusercontent.com/{att_repo}/{att_branch}/attachments/{jira_issue_id(issue_num)}/{quote(filename)}"


#def may_markup(gh_account: str) -> bool:
#    return gh_account if gh_account in ["@mocobeta", "@dweiss"] else f"`{gh_account}`"


def jira_timestamp_to_github_timestamp(ts: str) -> str:
    # convert Jira timestamp format to GitHub acceptable format
    # e.g., "2006-06-06T06:24:38.000+0000" -> "2006-06-06T06:24:38Z"
    return ts[:-9] + "Z"


def convert_issue(num: int, dump_dir: Path, output_dir: Path, account_map: dict[str, str], att_repo: str, att_branch: str) -> bool:
    jira_id = jira_issue_id(num)
    dump_file = jira_dump_file(dump_dir, num)
    if not dump_file.exists():
        logger.warning(f"Jira dump file not found: {dump_file}")
        return False

    with open(dump_file) as fp:
        o = json.load(fp)
        summary = extract_summary(o).strip()
        description = extract_description(o).strip()
        status = extract_status(o)
        issue_type = extract_issue_type(o)
        (reporter_name, reporter_dispname) = extract_reporter(o)
        (assignee_name, assignee_dispname) = extract_assignee(o)
        created = extract_created(o)
        updated = extract_updated(o)
        resolutiondate = extract_resolutiondate(o)
        fix_versions = extract_fixversions(o)
        versions = extract_versions(o)
        components = extract_components(o)
        attachments = extract_attachments(o)
        linked_issues = extract_issue_links(o)
        subtasks = extract_subtasks(o)
        pull_requests =extract_pull_requests(o)

        reporter_gh = account_map.get(reporter_name)
        reporter = f"{reporter_dispname} ({reporter_gh})" if reporter_gh else f"{reporter_dispname}"
        assignee_gh = account_map.get(assignee_name)
        assignee = f"{assignee_dispname} ({assignee_gh})" if assignee_gh else f"{assignee_dispname}"

        # make attachment list
        attachment_list_items = []
        att_replace_map = {}
        for (filename, cnt) in attachments:
            attachment_list_items.append(f"- [{filename}]({attachment_url(num, filename, att_repo, att_branch)})" + (f" (versions: {cnt})\n" if cnt > 1 else "\n"))
            att_replace_map[filename] = attachment_url(num, filename, att_repo, att_branch)

        # embed github issue number next to linked issue keys
        linked_issues_list_items = []
        for jira_key in linked_issues:
            linked_issues_list_items.append(f"- {jira_key} : [Jira link]({jira_issue_url(jira_key)})\n")
        
        # embed github issue number next to sub task keys
        subtasks_list_items = []
        for jira_key in subtasks:
            subtasks_list_items.append(f"- {jira_key} : [Jira link]({jira_issue_url(jira_key)})\n")

        # make pull requests list
        pull_requests_list = [f"- {x}\n" for x in pull_requests]

        body = f"""{convert_text(description, att_replace_map)}

---
### Jira information

Original Jira: {jira_issue_url(jira_id)}
Reporter: {reporter}
Assignee: {assignee}
Created: {created}
Updated: {updated}
Resolved: {resolutiondate}

Attachments:
{"".join(attachment_list_items)}

Issue Links:
{"".join(linked_issues_list_items)}
Sub-Tasks:
{"".join(subtasks_list_items)}

Pull Requests:
{"".join(pull_requests_list)}
"""

        def comment_author(author_name, author_dispname):
            author_gh = account_map.get(author_name)
            return f"{author_dispname} ({author_gh})" if author_gh else author_dispname
        
        comments = extract_comments(o)
        comments_data = []
        for (comment_author_name, comment_author_dispname, comment_body, comment_created, comment_updated) in comments:
            data = {
                "body": f"""{convert_text(comment_body, att_replace_map)}

Author: {comment_author(comment_author_name, comment_author_dispname)}
Created: {comment_created}
Updated: {comment_updated}
"""
            }
            if comment_created:
                data["created_at"] = jira_timestamp_to_github_timestamp(comment_created)
            comments_data.append(data)

        labels = []
        if issue_type and ISSUE_TYPE_TO_LABEL_MAP.get(issue_type):
            labels.append(ISSUE_TYPE_TO_LABEL_MAP.get(issue_type))
        # milestone?
        for v in fix_versions:
            if v:
                labels.append(f"fixVersion:{v}")
        for v in versions:
            if v:
                labels.append(f"affectsVersion:{v}")
        for c in components:
            if c.startswith("core"):
                labels.append(f"component:module/{c}")
            elif c in COMPONENT_TO_LABEL_MAP:
                labels.append(COMPONENT_TO_LABEL_MAP.get(c))

        data = {
            "issue": {
                "title": make_github_title(summary, jira_id),
                "body": body,
                "closed": status in ["Closed", "Resolved"],
                "labels": labels,
            },
            "comments": comments_data
        }
        if created:
            data["issue"]["created_at"] = jira_timestamp_to_github_timestamp(created)
        if updated:
            data["issue"]["updated_at"] = jira_timestamp_to_github_timestamp(updated)
        if resolutiondate:
            data["issue"]["closed_at"] = jira_timestamp_to_github_timestamp(resolutiondate)

        data_file = github_data_file(output_dir, num)
        with open(data_file, "w") as fp:
            json.dump(data, fp, indent=2)

    logger.debug(f"GitHub issue data created: {data_file}")
    return True


if __name__ == "__main__":
    github_att_repo = os.getenv("GITHUB_ATT_REPO")
    if not github_att_repo:
        print("Please set your GitHub attachment repo to GITHUB_ATT_REPO environment variable.")
        sys.exit(1)
    github_att_branch = os.getenv("GITHUB_ATT_BRANCH")
    if not github_att_repo:
        print("Please set your GitHub attachment branch to GITHUB_ATT_BRANCH environment variable.")
        sys.exit(1)

    parser = argparse.ArgumentParser()
    parser.add_argument('--issues', type=int, required=False, nargs='*', help='Jira issue number list to be downloaded')
    parser.add_argument('--min', type=int, dest='min', required=False, default=1, help='Minimum Jira issue number to be converted')
    parser.add_argument('--max', type=int, dest='max', required=False, help='Maximum Jira issue number to be converted')
    args = parser.parse_args()

    dump_dir = Path(__file__).resolve().parent.parent.joinpath(JIRA_DUMP_DIRNAME)
    if not dump_dir.exists():
        logger.error(f"Jira dump dir not exists: {dump_dir}")
        sys.exit(1)

    mappings_dir = Path(__file__).resolve().parent.parent.joinpath(MAPPINGS_DATA_DIRNAME)
    account_mapping_file = mappings_dir.joinpath(ACCOUNT_MAPPING_FILENAME)

    output_dir = Path(__file__).resolve().parent.parent.joinpath(GITHUB_IMPORT_DATA_DIRNAME)
    if not output_dir.exists():
        output_dir.mkdir()
    assert output_dir.exists()

    account_map = read_account_map(account_mapping_file) if account_mapping_file else {}

    issues = []
    if args.issues:
        issues = args.issues
    else:
        if args.max:
            issues.extend(list(range(args.min, args.max + 1)))
        else:
            issues.append(args.min)

    logger.info(f"Converting Jira issues to GitHub issues in {output_dir}")
    for num in issues:
        convert_issue(num, dump_dir, output_dir, account_map, github_att_repo, github_att_branch)
    
    logger.info("Done.")

