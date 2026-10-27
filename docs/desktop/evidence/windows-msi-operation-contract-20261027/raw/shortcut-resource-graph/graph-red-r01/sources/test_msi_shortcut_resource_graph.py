"""Actual fixed-JDK metadata golden from invented bytes; no install or user roots."""
import copy
import json
from pathlib import Path
import unittest
import msi_safety as m


def fixture():
    return json.loads((Path(__file__).with_name('fixtures')/'jdk17_shortcut_graph.json').read_text())


class ShortcutResourceGraph(unittest.TestCase):
    def graph(self,data):return m.operation_resource_graph(data['tables'],data['properties'])

    def reject(self,mutate):
        data=fixture();mutate(data['tables'])
        with self.assertRaises(AssertionError):self.graph(data)

    def test_fixed_jdk_optional_shortcuts_are_separate_from_program_directories(self):
        data=fixture();graph=self.graph(data)
        self.assertEqual(2,len(graph['shortcuts']))
        self.assertEqual({'DesktopFolder','ProgramMenuFolder'}, {v['knownFolder'] for v in graph['shortcutDirectories'].values()})
        self.assertEqual({'INSTALLDIR','dir40f84b56068d341da01a768174eb5e59'},set(graph['directories']))
        self.assertEqual({'ChatChatBarDesktop.exe'},set(graph['files'].values()))
        self.assertEqual({'JP_INSTALL_DESKTOP_SHORTCUT','JP_INSTALL_STARTMENU_SHORTCUT'}, {v['choiceProperty'] for v in graph['shortcuts'].values()})

    def test_unknown_component_condition_or_condition_on_program_file_refuses(self):
        for row in fixture()['tables']['Component']:
            self.reject(lambda t,key=row[0]:next(r for r in t['Component'] if r[0]==key).__setitem__(4,'UNKNOWN_CHOICE'))

    def test_shortcut_target_must_be_the_declared_main_launcher(self):
        for value in ('[INSTALLDIR]other.exe','[OtherFeature]','[#UNKNOWN]','C:\\Other\\tool.exe'):
            self.reject(lambda t,v=value:t['Shortcut'][0].__setitem__(4,v))

    def test_arguments_working_directory_and_extra_shortcut_fields_refuse(self):
        for index,value in ((5,'--data=Other'),(6,'unexpected'),(7,'1'),(8,'UNKNOWN_ICON'),(9,'1'),(10,'7'),(11,'DesktopFolder'),(12,'other')):
            self.reject(lambda t,i=index,v=value:t['Shortcut'][0].__setitem__(i,v))

    def test_name_and_menu_leaf_are_fixed_to_product_identity(self):
        for name in ('Other','..','ChatChatBarDesktop.lnk','[INSTALLDIR]','ChatChatBarDesktop '):
            self.reject(lambda t,v=name:t['Shortcut'][0].__setitem__(2,v))
            self.reject(lambda t,v=name:next(r for r in t['Directory'] if r[1]=='ProgramMenuFolder').__setitem__(2,v))

    def test_program_payload_may_not_be_owned_by_external_shortcut_component(self):
        self.reject(lambda t:t['File'][0].__setitem__(1,t['Shortcut'][0][3]))

    def test_external_component_needs_its_exact_shortcut_and_choice(self):
        self.reject(lambda t:t['Shortcut'][0].__setitem__(3,t['Shortcut'][1][3]))
        self.reject(lambda t:t['Component'].append(['unmapped','{00000000-0000-4000-8000-000000000000}','DesktopFolder','260','','marker']))

    def test_desktop_or_programs_root_is_never_an_empty_directory_remove_target(self):
        for folder in ('DesktopFolder','ProgramMenuFolder'):
            self.reject(lambda t,v=folder:t['RemoveFile'][0].__setitem__(3,v))

    def test_menu_leaf_remove_requires_that_leafs_own_shortcut_component(self):
        self.reject(lambda t:t['RemoveFile'][0].__setitem__(1,t['File'][0][1]))

    def test_empty_directory_removal_never_becomes_file_or_wildcard_cleanup(self):
        for value in ('*','*.lnk','ChatChatBarDesktop.lnk'):
            self.reject(lambda t,v=value:t['RemoveFile'][0].__setitem__(2,v))

    def test_external_create_folder_is_not_implicitly_permitted(self):
        self.reject(lambda t:t['CreateFolder'].append(['DesktopFolder',t['Shortcut'][1][3]]))

    def test_unknown_resource_family_still_fails_closed(self):
        self.reject(lambda t:t.__setitem__('Environment',[['UNKNOWN']]))

    def test_synthetic_profile_still_cannot_acquire_ordinary_links(self):
        data=fixture();data['properties']['ProductName']='ChatChatBarDesktopSynthetic'
        with self.assertRaisesRegex(AssertionError,'SYNTHETIC_SHORTCUT_REFUSED'):self.graph(data)

    def test_nested_or_desktop_menu_branch_is_not_a_known_menu_leaf(self):
        self.reject(lambda t:next(r for r in t['Directory'] if r[1]=='ProgramMenuFolder').__setitem__(1,'DesktopFolder'))
        self.reject(lambda t:next(r for r in t['Directory'] if r[1]=='ProgramMenuFolder').__setitem__(2,'A\\B'))


if __name__=='__main__':unittest.main()
