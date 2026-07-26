package com.songly.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import jakarta.persistence.Entity;
import java.util.Set;
import org.springframework.data.repository.Repository;

/**
 * Cała architektura z Claude.md §2 stoi na granicach modułów, których do tej
 * pory nie weryfikowało nic poza dyscypliną przy pisaniu kodu. Naruszenie tych
 * granic nie psuje żadnego testu ani builda — po prostu z modularnego monolitu
 * robi się stopniowo zwykły monolit, i orientujemy się dopiero wtedy, gdy
 * wydzielenie modułu do serwisu (Claude.md §10) okazuje się niemożliwe.
 *
 * <p>
 * Ten test zamienia tę konwencję w warunek przejścia CI. Reguły opisują stan
 * faktyczny na dzień powstania pliku — nie ma tu żadnych wyjątków „na już", bo
 * pierwszy wyjątek jest zwykle początkiem końca reguły.
 */
@AnalyzeClasses(packages = ModuleBoundariesTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

	static final String BASE = "com.songly";

	/**
	 * Moduły domenowe z Claude.md §3. {@code common} celowo poza listą — to
	 * współdzielona infrastruktura, z której wolno korzystać każdemu.
	 */
	private static final Set<String> DOMAIN_MODULES = Set.of("analytics", "community", "content", "identity",
			"matching", "notification", "nowplaying", "profile", "social");

	/**
	 * Publiczne API modułu: interfejsy (dziś {@code ProfileDirectory} i
	 * {@code SocialGraph}) oraz zdarzenia domenowe. Wszystko inne — encje,
	 * repozytoria, serwisy, DTO — jest jego prywatnym wnętrzem.
	 *
	 * <p>
	 * Kryterium jest celowo strukturalne, a nie lista nazw: nowy moduł dostaje tę
	 * samą dyscyplinę bez dopisywania czegokolwiek w tym pliku.
	 */
	private static boolean isPublishedApi(JavaClass target) {
		if (!target.getModifiers().contains(JavaModifier.PUBLIC)) {
			return false;
		}
		return target.isInterface() || target.getSimpleName().endsWith("Event");
	}

	private static String moduleOf(JavaClass clazz) {
		String packageName = clazz.getPackageName();
		if (!packageName.startsWith(BASE + ".")) {
			return null;
		}
		String remainder = packageName.substring(BASE.length() + 1);
		int dot = remainder.indexOf('.');
		String head = dot < 0 ? remainder : remainder.substring(0, dot);
		return DOMAIN_MODULES.contains(head) ? head : null;
	}

	private static final DescribedPredicate<JavaClass> IN_DOMAIN_MODULE = new DescribedPredicate<>(
			"leżą w module domenowym") {
		@Override
		public boolean test(JavaClass clazz) {
			return moduleOf(clazz) != null;
		}
	};

	private static final ArchCondition<JavaClass> ONLY_TOUCH_PUBLISHED_API = new ArchCondition<>(
			"sięgać do innych modułów wyłącznie przez ich publiczne interfejsy i zdarzenia") {
		@Override
		public void check(JavaClass clazz, ConditionEvents events) {
			String ownModule = moduleOf(clazz);
			clazz.getDirectDependenciesFromSelf().forEach(dependency -> {
				JavaClass target = dependency.getTargetClass().getBaseComponentType();
				String targetModule = moduleOf(target);
				if (targetModule == null || targetModule.equals(ownModule) || isPublishedApi(target)) {
					return;
				}
				events.add(SimpleConditionEvent.violated(clazz,
						"%s sięga do wnętrza modułu '%s' — dozwolone są tylko publiczne interfejsy i zdarzenia (%s)"
								.formatted(clazz.getName(), targetModule, dependency.getDescription())));
			});
		}
	};

	@ArchTest
	static final ArchRule moduly_siegaja_do_siebie_tylko_przez_opublikowane_api = classes().that(IN_DOMAIN_MODULE)
			.should(ONLY_TOUCH_PUBLISHED_API);

	/**
	 * Cykl między modułami znaczy, że granica jest fikcyjna — żadnego z nich nie da
	 * się już ruszyć bez drugiego, więc krok 3 z Claude.md §10 (wydzielenie modułu
	 * do serwisu) przestaje być wykonalny.
	 */
	@ArchTest
	static final ArchRule moduly_nie_tworza_cykli = SlicesRuleDefinition.slices()
			.matching(
					BASE + ".(analytics|community|content|identity|matching|notification|nowplaying|profile|social)..")
			.should().beFreeOfCycles();

	/**
	 * {@code common} to infrastruktura pod domeną, nie obok niej. Zależność w tę
	 * stronę oznaczałaby, że współdzielony kod wie o konkretnym module — a wtedy
	 * przestaje być współdzielony.
	 */
	@ArchTest
	static final ArchRule common_nie_zna_domeny = noClasses().that().resideInAPackage(BASE + ".common..").should()
			.dependOnClassesThat(IN_DOMAIN_MODULE);

	/**
	 * Encje i repozytoria trzymane poza {@code public} sprawiają, że pierwszą linią
	 * obrony granic jest kompilator, a nie ten test. Reguła pilnuje właśnie tego
	 * założenia — bez niego wystarczyłoby dopisać {@code public} przed encją, żeby
	 * reguła wyżej przestała cokolwiek znaczyć.
	 */
	@ArchTest
	static final ArchRule encje_nie_sa_publiczne = noClasses().that().areAnnotatedWith(Entity.class).should()
			.bePublic();

	@ArchTest
	static final ArchRule repozytoria_nie_sa_publiczne = noClasses().that().areAssignableTo(Repository.class).and()
			.resideInAPackage(BASE + "..").should().bePublic();

	/**
	 * Claude.md §2: żaden moduł nie zwraca na zewnątrz swoich encji JPA, zawsze
	 * DTO. Kontroler, który w ogóle nie widzi encji, nie ma jak jej zwrócić.
	 */
	@ArchTest
	static final ArchRule kontrolery_nie_dotykaja_encji = noClasses().that().haveSimpleNameEndingWith("Controller")
			.should().dependOnClassesThat().areAnnotatedWith(Entity.class);
}
